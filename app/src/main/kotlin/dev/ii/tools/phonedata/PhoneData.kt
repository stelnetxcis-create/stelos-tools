package dev.ii.tools.phonedata

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * What the shell used to read over ADB or KDE Connect (PROTOCOL.md, "Phone data"):
 * the next alarm clock, battery and network (`status`), and the contacts with photos.
 *
 * Lives in the notification listener's process. Status goes on every connect and on
 * change while the link is up; a changed alarm brings the link up for itself, since the
 * PC's clock app should not show a stale one. Contacts go only when the PC asks with a
 * version that differs, photos only those it lacks.
 */
object PhoneData {
    data class Peripheral(
        val id: String,
        val name: String,
        val kind: String,
        val level: Int,
    )

    private const val PREFS = "phoneData"
    private const val ENABLED = "enabled"
    private const val BATTERY_EVERY_MS = 30_000L
    private const val PERIPHERAL_COALESCE_MS = 400L
    private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val CONTACTS = Manifest.permission.READ_CONTACTS
    val BLUETOOTH = Manifest.permission.BLUETOOTH_CONNECT

    private val btGetBatteryLevel: Method? by lazy {
        runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel") }.getOrNull()
    }
    private val btIsConnected: Method? by lazy {
        runCatching { BluetoothDevice::class.java.getMethod("isConnected") }.getOrNull()
    }

    private var ctx: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null
    private var btDebounceJob: Job? = null
    private var lastBatterySent = 0L
    private var lastBatteryKey = ""
    private var lastPeripheralsSent = 0L
    private var lastPeripheralsKey = ""
    private var lastPeripheralsIds = ""

    private val _peripherals = MutableStateFlow<List<Peripheral>>(emptyList())
    val peripherals = _peripherals.asStateFlow()

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)
    fun canReadContacts(c: Context) = c.checkSelfPermission(CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canReadBluetooth(c: Context) = c.checkSelfPermission(BLUETOOTH) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        ctx?.let { if (on) start(it) else stop() }
        if (!on) PhonePerf.stopAll()
        syncBluetoothReceiver()
        LinkHub.announceCaps()
    }

    fun onLinkConnected(c: Context) {
        if (ctx == null) ctx = c.applicationContext
        syncBluetoothReceiver()
        sendStatus()
    }

    fun onLinkDisconnected() {
        syncBluetoothReceiver()
    }

    fun onBluetoothPermissionChanged(c: Context) {
        if (ctx == null) ctx = c.applicationContext
        syncBluetoothReceiver()
        refreshPeripherals(c)
        if (LinkHub.isConnected() && isEnabled(c)) sendStatus()
    }

    fun refreshPeripherals(c: Context) {
        scope.launch {
            _peripherals.value = if (canReadBluetooth(c)) readPeripherals(c) else emptyList()
        }
    }

    /** What this phone offers, for `caps`. */
    fun caps(c: Context): List<String> {
        if (!isEnabled(c) || ctx == null) return emptyList()
        return if (canReadContacts(c)) listOf("status", "contacts") else listOf("status")
    }

    fun start(c: Context) {
        ctx = c
        if (receiver != null || !isEnabled(c)) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    // The PC's clock should never show an alarm the phone no longer has.
                    AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> {
                        if (LinkHub.isConnected()) sendStatus() else LinkHub.notifBurst()
                    }
                    Intent.ACTION_BATTERY_CHANGED -> onBattery()
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> if (LinkHub.isConnected()) sendStatus()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        c.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        receiver = r
        syncBluetoothReceiver()
        // The network the phone is on changes what a routine on the PC can condition on.
        c.getSystemService(ConnectivityManager::class.java)?.let { cm ->
            netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val type = network(caps)
                    if (type != lastNet) {
                        lastNet = type
                        if (LinkHub.isConnected()) sendStatus()
                    }
                }
                override fun onLost(network: Network) {
                    if (lastNet != "none") {
                        lastNet = "none"
                        if (LinkHub.isConnected()) sendStatus()
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        }
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNet = ""

    private fun network(caps: NetworkCapabilities?) = when {
        caps == null -> "none"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    fun stop() {
        btDebounceJob?.cancel()
        btDebounceJob = null
        btReceiver?.let { r -> ctx?.let { runCatching { it.unregisterReceiver(r) } } }
        btReceiver = null
        receiver?.let { r -> ctx?.let { runCatching { it.unregisterReceiver(r) } } }
        receiver = null
        netCallback?.let { cb -> ctx?.getSystemService(ConnectivityManager::class.java)?.let { runCatching { it.unregisterNetworkCallback(cb) } } }
        netCallback = null
    }

    private fun syncBluetoothReceiver() {
        val c = ctx ?: return
        val active = LinkHub.isConnected() && isEnabled(c) && canReadBluetooth(c)
        if (!active) {
            btDebounceJob?.cancel()
            btDebounceJob = null
            btReceiver?.let { r -> runCatching { c.unregisterReceiver(r) } }
            btReceiver = null
            if (!isEnabled(c) || !canReadBluetooth(c)) _peripherals.value = emptyList()
            return
        }
        if (btReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onBluetoothBroadcast(context, intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        c.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        btReceiver = r
    }

    private fun onBluetoothBroadcast(c: Context, intent: Intent) {
        if (!LinkHub.isConnected() || !isEnabled(c) || !canReadBluetooth(c)) return
        @Suppress("DEPRECATION")
        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val extraLevel = if (intent.action == ACTION_BATTERY_LEVEL_CHANGED) {
            intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1).takeIf { it in 0..100 }
        } else {
            null
        }
        val addr = dev?.address
        btDebounceJob?.cancel()
        btDebounceJob = scope.launch {
            val list = readPeripherals(c, if (addr != null && extraLevel != null) mapOf(addr to extraLevel) else emptyMap())
            _peripherals.value = list
            val ids = list.joinToString(",") { it.id }
            val key = peripheralsKey(list)
            if (key == lastPeripheralsKey) return@launch
            val now = System.currentTimeMillis()
            val wait = if (ids != lastPeripheralsIds || now - lastPeripheralsSent >= BATTERY_EVERY_MS) {
                PERIPHERAL_COALESCE_MS
            } else {
                (BATTERY_EVERY_MS - (now - lastPeripheralsSent)).coerceAtLeast(PERIPHERAL_COALESCE_MS)
            }
            delay(wait)
            if (LinkHub.isConnected() && isEnabled(c)) sendStatus()
        }
    }

    /** Battery news only while the link is up, and not more than every 30 s unless plugging changed. */
    private fun onBattery() {
        if (!LinkHub.isConnected()) return
        val b = battery() ?: return
        val key = "${b.optInt("level")}/${b.optBoolean("charging")}"
        val now = System.currentTimeMillis()
        val pluggedChanged = key.substringAfter('/') != lastBatteryKey.substringAfter('/')
        if (key == lastBatteryKey || (!pluggedChanged && now - lastBatterySent < BATTERY_EVERY_MS)) return
        sendStatus()
    }

    /** On connect (LinkHub), on a changed alarm, on battery news. */
    fun sendStatus() {
        val c = ctx ?: return
        if (!isEnabled(c)) return
        scope.launch {
            val m = JSONObject().put("t", "status")
            val b = battery()
            m.put("battery", b ?: JSONObject.NULL)
            m.put("net", network(c))
            m.put("ringer", when (c.getSystemService(AudioManager::class.java)?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            m.put("alarm", alarm(c) ?: JSONObject.NULL)
            val btList = if (canReadBluetooth(c)) readPeripherals(c) else emptyList()
            _peripherals.value = btList
            m.put("peripherals", peripheralsToJson(btList))
            val now = System.currentTimeMillis()
            if (b != null) {
                lastBatteryKey = "${b.optInt("level")}/${b.optBoolean("charging")}"
                lastBatterySent = now
            }
            lastPeripheralsIds = btList.joinToString(",") { it.id }
            lastPeripheralsKey = peripheralsKey(btList)
            lastPeripheralsSent = now
            LinkHub.sendJson(m)
        }
    }

    private fun alarm(c: Context): JSONObject? {
        val next = c.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        return JSONObject().put("at", next.triggerTime).put("pkg", next.showIntent?.creatorPackage.orEmpty())
    }

    private fun battery(): JSONObject? {
        val c = ctx ?: return null
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "overVoltage"
            else -> "unknown"
        }
        val plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> ""
        }
        return JSONObject()
            .put("level", level * 100 / scale)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plug", plug)
            .put("temp", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("health", health)
    }

    private fun network(c: Context): JSONObject {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val type = network(caps)
        // 0–4 bars, from the mobile radio (no permission needed for the level).
        val signal = runCatching { c.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }.getOrNull() ?: -1
        return JSONObject().put("type", type).put("signal", signal)
    }

    @SuppressLint("MissingPermission")
    private fun readPeripherals(c: Context, overrides: Map<String, Int> = emptyMap()): List<Peripheral> {
        if (!canReadBluetooth(c)) return emptyList()
        val bm = c.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = bm.adapter ?: return emptyList()
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return emptyList()

        val gattAddresses = HashSet<String>()
        for (profile in intArrayOf(BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER)) {
            runCatching { bm.getConnectedDevices(profile) }.getOrNull()?.forEach { it.address?.let(gattAddresses::add) }
        }
        val out = ArrayList<Peripheral>()

        for (device in runCatching { adapter.bondedDevices }.getOrNull().orEmpty()) {
            val addr = device.address ?: continue
            if (runCatching { device.bondState }.getOrNull() != BluetoothDevice.BOND_BONDED) continue
            if (!isDeviceConnected(device, gattAddresses)) continue

            val reflectedLevel = runCatching { (btGetBatteryLevel?.invoke(device) as? Int) ?: -1 }.getOrDefault(-1)
            val level = if (reflectedLevel in 0..100) reflectedLevel else overrides[addr] ?: -1
            // Each bud's and the case's levels sit behind BLUETOOTH_PRIVILEGED (getMetadata),
            // which no installed app gets: only the overall level is readable.
            if (level !in 0..100) continue

            val name = runCatching { device.alias?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() } }
                .getOrNull()
                ?.trim()
                ?: "Bluetooth"
            val btClass = runCatching { device.bluetoothClass }.getOrNull()
            out.add(
                Peripheral(
                    id = sha(addr).take(16),
                    name = name,
                    kind = classifyKind(btClass, name),
                    level = level,
                )
            )
        }
        return out.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceConnected(device: BluetoothDevice, gattAddresses: Set<String>): Boolean {
        if (Build.VERSION.SDK_INT >= 37) {
            val ok = runCatching {
                device.isConnected(BluetoothDevice.TRANSPORT_BREDR) || device.isConnected(BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
            if (ok != null) return ok
        }
        val reflected = runCatching { btIsConnected?.invoke(device) as? Boolean }.getOrNull()
        if (reflected == true) return true
        return device.address in gattAddresses
    }

    private fun classifyKind(btClass: BluetoothClass?, name: String): String {
        val lower = name.lowercase()
        val devClass = btClass?.deviceClass ?: -1
        val major = btClass?.majorDeviceClass ?: -1
        val looksLikeEarbuds = listOf("bud", "airdot", "airpod", "freebuds", "earbud", "wf-", "tws", "in-ear")
            .any { lower.contains(it) }
        val looksLikeWatch = listOf("watch", "band", "garmin", "amazfit", "bip", "fit")
            .any { lower.contains(it) }
        return when (devClass) {
            BluetoothClass.Device.WEARABLE_WRIST_WATCH -> "watch"
            BluetoothClass.Device.PERIPHERAL_KEYBOARD -> "keyboard"
            BluetoothClass.Device.PERIPHERAL_POINTING,
            BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> "mouse"
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "speaker"
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> if (looksLikeEarbuds) "earbuds" else "headset"
            else -> when {
                looksLikeWatch -> "watch"
                looksLikeEarbuds -> "earbuds"
                lower.contains("headphone") || lower.contains("headset") || lower.contains("wh-") -> "headset"
                lower.contains("speaker") || lower.contains("soundbar") -> "speaker"
                lower.contains("keyboard") || lower.contains("keychron") -> "keyboard"
                lower.contains("mouse") || lower.contains("trackpad") -> "mouse"
                major == BluetoothClass.Device.Major.AUDIO_VIDEO -> "headset"
                major == BluetoothClass.Device.Major.WEARABLE -> "watch"
                else -> "other"
            }
        }
    }

    private fun peripheralsKey(list: List<Peripheral>) = list.joinToString("|") {
        "${it.id}:${it.level}"
    }

    private fun peripheralsToJson(list: List<Peripheral>) = JSONArray().apply {
        for (p in list) {
            val o = JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("kind", p.kind)
                .put("level", p.level)
            put(o)
        }
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    private data class Contact(val json: JSONObject, val photoKey: String, val contactId: Long)

    /** The PC asked, with the version it has: the list only when it changed. */
    fun onContactsAsked(have: String) {
        val c = ctx ?: return
        if (!isEnabled(c) || !canReadContacts(c)) return
        scope.launch {
            val contacts = runCatching { readContacts(c) }.getOrNull() ?: return@launch
            val list = JSONArray().apply { contacts.forEach { put(it.json) } }
            val hash = sha(list.toString())
            if (hash == have) return@launch
            LinkHub.sendJson(JSONObject().put("t", "contacts").put("hash", hash).put("list", list))
        }
    }

    /** Photos the PC lacks, one file each, in turn. */
    fun onPhotosAsked(keys: List<String>) {
        val c = ctx ?: return
        if (!isEnabled(c) || !canReadContacts(c) || keys.isEmpty()) return
        scope.launch {
            val byKey = runCatching { readContacts(c) }.getOrNull()?.associateBy { it.photoKey } ?: return@launch
            for (key in keys.take(500)) {
                val contact = byKey[key] ?: continue
                val bytes = photo(c, contact.contactId) ?: continue
                LinkHub.upload("$key.jpg", bytes.size.toLong(), { bytes.inputStream() }, "contactPhoto", "", waitForLink = false) { }
            }
        }
    }

    private fun readContacts(c: Context): List<Contact> {
        val byId = LinkedHashMap<Long, JSONObject>()
        val phones = HashMap<Long, JSONArray>()
        val emails = HashMap<Long, JSONArray>()
        val birthdays = HashMap<Long, JSONObject>()
        val orgs = HashMap<Long, String>()
        val names = HashMap<Long, Pair<String, String>>()
        val cr = c.contentResolver
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE",
        )?.use { cur ->
            while (cur.moveToNext()) {
                byId[cur.getLong(0)] = JSONObject().put("id", sha(cur.getString(1).orEmpty()).take(16)).put("displayName", cur.getString(2).orEmpty())
            }
        }
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                if (id !in byId) continue
                val d1 = cur.getString(2).orEmpty()
                when (cur.getString(1)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones.getOrPut(id) { JSONArray() }.put(
                        JSONObject().put("value", d1)
                            .put("normalized", cur.getString(5) ?: d1.filter { it.isDigit() || it == '+' })
                            .put("type", phoneType(cur.getInt(3))),
                    )
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails.getOrPut(id) { JSONArray() }.put(
                        JSONObject().put("value", d1).put("type", if (cur.getInt(3) == ContactsContract.CommonDataKinds.Email.TYPE_WORK) "work" else "home"),
                    )
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> if (d1.isNotEmpty()) orgs[id] = d1
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE ->
                        names[id] = cur.getString(3).orEmpty() to cur.getString(4).orEmpty()
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                        if (cur.getInt(3) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) birthday(d1)?.let { birthdays[id] = it }
                }
            }
        }
        return byId.map { (id, o) ->
            names[id]?.let { (given, family) -> o.put("givenName", given).put("familyName", family) }
            o.put("organization", orgs[id].orEmpty())
            o.put("phones", phones[id] ?: JSONArray())
            o.put("emails", emails[id] ?: JSONArray())
            o.put("birthday", birthdays[id] ?: JSONObject.NULL)
            // The photo's own hash: a changed photo is a new file on the PC.
            val key = photo(c, id)?.let { sha(it).take(16) }.orEmpty()
            o.put("photo", key)
            Contact(o, key, id)
        }
    }

    private fun photo(c: Context, contactId: Long): ByteArray? = runCatching {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        ContactsContract.Contacts.openContactPhotoInputStream(c.contentResolver, uri, false)?.use { it.readBytes() }
    }.getOrNull()

    private fun phoneType(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        else -> "other"
    }

    /** "1990-05-17" or "--05-17" → {year, month, day}, as the shell's vCard reader gives. */
    private fun birthday(raw: String): JSONObject? {
        val m = Regex("""(?:(\d{4})-)?-?(\d{2})-(\d{2})""").find(raw) ?: return null
        val (year, month, day) = m.destructured
        return JSONObject().put("year", year.toIntOrNull() ?: JSONObject.NULL).put("month", month.toInt()).put("day", day.toInt())
    }

    private fun sha(s: String): String = sha(s.toByteArray())
    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
