package dev.ii.tools.link

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN for a paired PC. The shell lists its adapters' MACs and subnet
 * broadcasts in every `hello`; they are kept with the pairing (per PC, by fingerprint),
 * since they are needed exactly when the PC is off and cannot say them.
 */
object Wake {
    private const val PREFS = "link"
    private const val KEY = "wake"

    fun remember(ctx: Context, pc: String, targets: JSONArray?) {
        if (targets == null || pc.isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("$KEY:$pc", targets.toString()).apply()
    }

    fun forget(ctx: Context, pc: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("$KEY:$pc").apply()
    }

    /** The PC the home controls can be woken. */
    fun available(ctx: Context): Boolean = targets(ctx, LinkHub.selectedKey()).length() > 0

    /** [pc]'s adapters, for another PC on its LAN to wake it (`wakeFor`). */
    fun targetsOf(ctx: Context, pc: String?): JSONArray = targets(ctx, pc)

    private fun targets(ctx: Context, pc: String?): JSONArray =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("$KEY:$pc", "[]")) }.getOrDefault(JSONArray())

    /** The magic packet (6 × FF, then the MAC 16 times) to each adapter, on ports 9 and 7. */
    suspend fun send(ctx: Context, pc: String?): Boolean = withContext(Dispatchers.IO) {
        val targets = targets(ctx, pc)
        var sent = false
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                for (i in 0 until targets.length()) {
                    val t = targets.optJSONObject(i) ?: continue
                    val mac = t.optString("mac").split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
                    if (mac.size != 6) continue
                    val packet = ByteArray(6) { 0xFF.toByte() } + List(16) { mac }.flatten()
                    val addresses = listOfNotNull(t.optString("broadcast").ifEmpty { null }, "255.255.255.255").distinct()
                    for (addr in addresses) for (port in intArrayOf(9, 7)) {
                        runCatching {
                            sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName(addr), port))
                            sent = true
                        }
                    }
                }
            }
        }
        sent
    }
}
