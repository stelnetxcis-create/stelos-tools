package dev.ii.tools.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** One app that has posted a notification (or is blocked), newest first. */
data class NotifApp(val pkg: String, val name: String, val blocked: Boolean)

/**
 * The phone's notifications on the PC, over ii link (PROTOCOL.md, "Notifications").
 *
 * Runs in the notification listener, so it sees every notification with the app
 * closed. A new one opens the link for a burst (LinkHub keeps it 2 min after the last);
 * on every connect the PC gets the whole current list, so nothing needs queueing while
 * the link is down. The PC can dismiss, press an action or reply.
 *
 * Mirrored: what the user would see and could swipe away. Not ongoing ones (live
 * updates have their own path), group summaries, media, calls, local-only ones, or
 * this app's and KDE Connect's own.
 */
object NotifMirror {
    private const val PREFS = "notifs"
    private const val ENABLED = "enabled"
    private const val BLOCKED = "blocked"
    private const val SEEN = "seen"
    private const val ICONS_SENT = "iconsSent"
    private const val APPS_DEBOUNCE_MS = 1000L
    private const val SEEN_MAX = 200
    private const val IMAGE_MAX = 480
    private const val AVATAR_MAX = 128
    private const val ICON_SIZE = 96
    /** The system's own placeholders ("2 more notifications", media holders), never news. */
    private val SYSTEM = setOf("android", "com.android.systemui")

    private data class SeenApp(val pkg: String, val name: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var appCtx: Context? = null
    private var listener: NotificationListenerService? = null
    private var loaded = false
    private var blocked = emptySet<String>()
    private val seen = mutableListOf<SeenApp>()
    private var appsPushJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _apps = MutableStateFlow<List<NotifApp>>(emptyList())
    val apps: StateFlow<List<NotifApp>> = _apps.asStateFlow()

    /** Icons go one at a time (one file per phone on the link), in the order they are needed. */
    private val iconQueue = Channel<String>(Channel.UNLIMITED)
    private val iconsQueued = mutableSetOf<String>()

    init {
        scope.launch {
            for (pkg in iconQueue) sendIcon(pkg)
        }
    }

    fun load(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        synchronized(lock) {
            if (loaded) return
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            blocked = sp.getStringSet(BLOCKED, emptySet())?.toSet().orEmpty()
            seen.clear()
            val seenPkgs = HashSet<String>()
            val raw = sp.getString(SEEN, null)
            if (!raw.isNullOrEmpty()) {
                val arr = runCatching { JSONArray(raw) }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val pkg = o.optString("pkg")
                        if (pkg.isEmpty() || !seenPkgs.add(pkg)) continue
                        seen += SeenApp(pkg, o.optString("name").ifEmpty { appLabel(app, pkg) })
                    }
                }
            }
            loaded = true
            _apps.value = buildListLocked(app)
        }
    }

    private fun saveSeenLocked(ctx: Context) {
        val arr = JSONArray()
        seen.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("name", it.name)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SEEN, arr.toString()).apply()
    }

    private fun buildListLocked(ctx: Context): List<NotifApp> {
        val seenPkgs = HashSet<String>(seen.size)
        val out = ArrayList<NotifApp>(seen.size + blocked.size)
        for (s in seen) {
            seenPkgs += s.pkg
            out += NotifApp(s.pkg, s.name, s.pkg in blocked)
        }
        val extraBlocked = blocked.filter { it !in seenPkgs }
            .map { NotifApp(it, appLabel(ctx, it), blocked = true) }
            .sortedBy { it.name.lowercase() }
        out += extraBlocked
        return out
    }

    private fun isBlocked(pkg: String): Boolean = synchronized(lock) { pkg in blocked }

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        _running.value = on && listener != null
        LinkHub.announceCaps()
        if (on) LinkHub.onLinkReady { sendAll() }
    }

    fun start(service: NotificationListenerService) {
        listener = service
        load(service)
        seedFromActive(service)
        _running.value = isEnabled(service)
        LinkHub.announceCaps()
        // The link may have come up first (the app just updated): its hello found no
        // listener, so the list goes now.
        if (LinkHub.isOnline()) sendAll()
    }

    fun onListenerGone(service: NotificationListenerService) {
        if (listener !== service) return
        listener = null
        _running.value = false
        LinkHub.announceCaps()
    }

    private fun active(): Boolean = _running.value

    private fun isMirrorableCandidate(sbn: StatusBarNotification, ownPackage: String): Boolean {
        val n = sbn.notification
        val pkg = sbn.packageName
        if (pkg == ownPackage || pkg.startsWith("org.kde.kdeconnect") || pkg in SYSTEM) return false
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_LOCAL_ONLY) != 0) return false
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT) return false
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        val e = n.extras
        return !e.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() || !e.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    fun shouldMirror(sbn: StatusBarNotification, ownPackage: String): Boolean {
        if (!isMirrorableCandidate(sbn, ownPackage)) return false
        return !isBlocked(sbn.packageName)
    }

    private fun seedFromActive(service: NotificationListenerService) {
        val activeList = runCatching { service.activeNotifications }.getOrNull().orEmpty()
            .filter { isMirrorableCandidate(it, service.packageName) }
            .sortedByDescending { it.postTime }
        if (activeList.isEmpty()) return
        val changed = synchronized(lock) {
            val existing = seen.map { it.pkg }.toHashSet()
            var added = false
            for (sbn in activeList) {
                val pkg = sbn.packageName
                if (existing.add(pkg)) {
                    seen += SeenApp(pkg, appLabel(service, pkg))
                    added = true
                }
            }
            if (added) {
                saveSeenLocked(service)
                _apps.value = buildListLocked(service)
            }
            added
        }
        if (changed) scheduleAppsPush(service)
    }

    private fun recordSeen(ctx: Context, pkg: String) {
        load(ctx)
        val label = appLabel(ctx, pkg)
        // Reordering stays local; the PC only hears about apps it has not seen yet.
        val isNew = synchronized(lock) {
            val first = seen.firstOrNull()
            if (first != null && first.pkg == pkg && first.name == label) return
            val fresh = seen.removeAll { it.pkg == pkg }.not()
            seen.add(0, SeenApp(pkg, label))
            while (seen.size > SEEN_MAX) seen.removeAt(seen.lastIndex)
            saveSeenLocked(ctx)
            _apps.value = buildListLocked(ctx)
            fresh
        }
        if (isNew) scheduleAppsPush(ctx)
    }

    fun setBlocked(ctx: Context, pkg: String, block: Boolean) {
        if (pkg.isBlank()) return
        load(ctx)
        val app = ctx.applicationContext
        val changed = synchronized(lock) {
            val was = pkg in blocked
            if (was == block) return
            blocked = if (block) blocked + pkg else blocked - pkg
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(BLOCKED, blocked).apply()
            _apps.value = buildListLocked(app)
            true
        }
        if (!changed) return
        scheduleAppsPush(app)
        val l = listener ?: return
        if (!active() || !LinkHub.isOnline()) return
        scope.launch {
            val activeList = runCatching { l.activeNotifications }.getOrNull().orEmpty()
            if (block) {
                activeList.filter { it.packageName == pkg }.forEach { sbn ->
                    LinkHub.sendJson(JSONObject().put("t", "notifGone").put("key", sbn.key))
                }
            } else if (activeList.any { it.packageName == pkg && shouldMirror(it, l.packageName) }) {
                sendAll()
            }
        }
    }

    // ── Phone → PC ────────────────────────────────────────────────────────

    fun onPosted(sbn: StatusBarNotification) {
        val l = listener ?: return
        if (isMirrorableCandidate(sbn, l.packageName)) recordSeen(l, sbn.packageName)
        if (!active() || !shouldMirror(sbn, l.packageName)) return
        if (LinkHub.isOnline()) {
            scope.launch { LinkHub.sendJson(JSONObject().put("t", "notif").put("n", describe(l, sbn))) }
            queueIcon(l, sbn.packageName)
        }
        // Up or not, the burst keeps (or brings) the link up; a new link sends everything.
        LinkHub.notifBurst()
    }

    fun onRemoved(sbn: StatusBarNotification) {
        val l = listener ?: return
        if (!active() || !shouldMirror(sbn, l.packageName) || !LinkHub.isOnline()) return
        scope.launch { LinkHub.sendJson(JSONObject().put("t", "notifGone").put("key", sbn.key)) }
    }

    /** The link came up: the whole current list, then any icon the PC has not had. */
    fun sendAll() {
        val l = listener ?: return
        if (!active()) return
        scope.launch {
            val list = runCatching { l.activeNotifications }.getOrNull().orEmpty()
                .filter { shouldMirror(it, l.packageName) }
            val arr = JSONArray()
            list.forEach { arr.put(describe(l, it)) }
            LinkHub.sendJson(JSONObject().put("t", "notifs").put("list", arr))
            list.map { it.packageName }.distinct().forEach { queueIcon(l, it) }
        }
    }

    fun onShellHello(ctx: Context) {
        load(ctx)
        if (!LinkHub.supportsNotifFilter()) return
        appsPushJob?.cancel()
        appsPushJob = scope.launch { sendApps(ctx) }
    }

    private fun scheduleAppsPush(ctx: Context) {
        if (!LinkHub.supportsNotifFilter()) return
        val app = ctx.applicationContext
        appsPushJob?.cancel()
        appsPushJob = scope.launch {
            delay(APPS_DEBOUNCE_MS)
            sendApps(app)
        }
    }

    private fun sendApps(ctx: Context) {
        if (!LinkHub.supportsNotifFilter()) return
        val list = _apps.value
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().put("pkg", a.pkg).put("name", a.name).put("blocked", a.blocked))
        }
        LinkHub.sendJson(JSONObject().put("t", "notifApps").put("list", arr))
        list.forEach { queueIcon(ctx, it.pkg) }
    }

    fun onFilterMessage(ctx: Context, m: JSONObject) {
        val pkg = m.optString("pkg")
        if (pkg.isEmpty()) return
        setBlocked(ctx, pkg, m.optBoolean("blocked"))
    }

    private fun describe(ctx: Context, sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val o = JSONObject()
            .put("key", sbn.key)
            .put("pkg", sbn.packageName)
            .put("app", appLabel(ctx, sbn.packageName))
            .put("title", title)
            .put("text", text)
            .put("time", sbn.postTime)
        images(ctx, n).let { (image, avatar) ->
            image?.let { o.put("image", it) }
            avatar?.let { o.put("avatar", it) }
        }
        val actions = JSONArray()
        n.actions?.forEachIndexed { i, a ->
            val input = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            actions.put(
                JSONObject().put("i", i).put("label", a.title?.toString().orEmpty())
                    .put("reply", input != null).put("hint", input?.label?.toString().orEmpty()),
            )
        }
        if (actions.length() > 0) o.put("actions", actions)
        return o
    }

    /**
     * The content picture and the sender's photo, apart (small JPEGs, base64): a big
     * picture is content; the large icon is a person (a chat's sender, a mail's
     * contact) when square, and content (a video thumbnail, a product) when not.
     */
    private fun images(ctx: Context, n: Notification): Pair<String?, String?> {
        val e = n.extras
        val picture = runCatching {
            e.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                ?: e.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.loadDrawable(ctx)?.toBitmap()
        }.getOrNull()
        val large = runCatching { n.getLargeIcon()?.loadDrawable(ctx)?.toBitmap() }.getOrNull()
        val largeIsContent = large != null && kotlin.math.abs(large.width.toFloat() / large.height.coerceAtLeast(1) - 1f) > 0.08f
        val content = picture ?: large?.takeIf { largeIsContent }
        // Chat apps (Telegram) may leave the large icon out and put the photo on the
        // last message's sender.
        val person = large?.takeIf { !largeIsContent } ?: runCatching {
            Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(e.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java))
                .lastOrNull()?.senderPerson?.icon?.loadDrawable(ctx)?.toBitmap()
        }.getOrNull()
        return encode(content, IMAGE_MAX) to encode(person, AVATAR_MAX)
    }

    private fun encode(bmp: Bitmap?, max: Int): String? = runCatching {
        if (bmp == null) return null
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f) bmp.scale((bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1)) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 78, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private val labels = HashMap<String, String>()
    private fun appLabel(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    // ── Icons: once per app per PC, kept there for good ───────────────────

    private fun queueIcon(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val missing = LinkHub.onlinePcs("notifs").any { pkg !in prefs.getStringSet(sentKey(it.id), emptySet()).orEmpty() }
        synchronized(iconsQueued) {
            if (!missing || !iconsQueued.add(pkg)) return
        }
        iconQueue.trySend(pkg)
    }

    private fun sentKey(pcId: String) = "$ICONS_SENT:$pcId"

    private suspend fun sendIcon(pkg: String) {
        val ctx = appCtx ?: listener ?: return
        try {
            val png = runCatching {
                val out = ByteArrayOutputStream()
                ctx.packageManager.getApplicationIcon(pkg).toBitmap(ICON_SIZE, ICON_SIZE).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }.getOrNull() ?: return
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Every PC that mirrors notifications keeps its own copy.
            for (pc in LinkHub.onlinePcs("notifs")) {
                val key = sentKey(pc.id)
                if (pkg in prefs.getStringSet(key, emptySet()).orEmpty()) continue
                val why = LinkHub.upload("$pkg.png", png.size.toLong(), { png.inputStream() }, "appIcon", "", waitForLink = false, toPc = pc.fingerprint) { }
                if (why == null) prefs.edit().putStringSet(key, prefs.getStringSet(key, emptySet()).orEmpty() + pkg).apply()
            }
        } finally {
            synchronized(iconsQueued) { iconsQueued.remove(pkg) }
        }
    }

    // ── PC → phone ────────────────────────────────────────────────────────

    fun command(m: JSONObject) {
        val l = listener ?: return
        if (!active()) return
        val key = m.optString("key")
        val sbn = runCatching { l.activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return
        runCatching {
            when (m.optString("a")) {
                "dismiss" -> l.cancelNotification(key)
                "action" -> sbn.notification.actions?.getOrNull(m.optInt("i", -1))?.actionIntent?.send()
                "reply" -> {
                    val action = sbn.notification.actions?.getOrNull(m.optInt("i", -1)) ?: return
                    val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, m.optString("text")) })
                    action.actionIntent.send(l, 0, intent)
                }
            }
        }
    }
}
