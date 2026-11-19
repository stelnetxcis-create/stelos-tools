package dev.ii.tools.link

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The PC away from home, over Tailscale. The shell announces its tailnet address in
 * `hello.routes` while its *Away from home* switch is on; the phone tries it when the
 * PC's LAN is not around, and can turn the Tailscale app's VPN on (and off again) through
 * the broadcasts that app takes from automation tools (`CONNECT_VPN`/`DISCONNECT_VPN`).
 */
object Tailnet {
    const val PACKAGE = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val PREFS = "tailnet"
    private const val AUTO = "auto"
    /** The VPN is up because this app turned it on: it may turn it off again. */
    private const val OURS = "ours"

    /** 100.64.0.0/10, Tailscale's address range (CGNAT). */
    fun isTailnet(host: String?): Boolean {
        val p = host?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }

    fun installed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Turn Tailscale on by itself when the PC's LAN is not around. Off by default. */
    fun auto(ctx: Context): Boolean = prefs(ctx).getBoolean(AUTO, false)

    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(AUTO, on).apply()
    }

    fun ours(ctx: Context): Boolean = prefs(ctx).getBoolean(OURS, false)

    /** The phone's traffic goes through a VPN holding a tailnet address. */
    fun isUp(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                cm.getLinkProperties(n)?.linkAddresses?.any { isTailnet(it.address.hostAddress) } == true
        }
    }

    /**
     * The phone sits on the PC's LAN now: one of [hosts] is inside the subnet of a
     * Wi-Fi or Ethernet network the phone is on (a VPN does not count).
     */
    fun onLanOf(ctx: Context, hosts: List<String>): Boolean {
        val targets = hosts.filter { !isTailnet(it) }.mapNotNull { h ->
            runCatching { InetAddress.getByName(h) }.getOrNull() as? Inet4Address
        }
        if (targets.isEmpty()) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@any false
            cm.getLinkProperties(n)?.linkAddresses.orEmpty().any { la ->
                val own = la.address as? Inet4Address ?: return@any false
                targets.any { sameSubnet(own, it, la.prefixLength) }
            }
        }
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        if (prefix !in 1..32) return false
        val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        fun Inet4Address.bits() = address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
        return (a.bits() and mask) == (b.bits() and mask)
    }

    /** Asks the Tailscale app to connect, then waits for its VPN; true once it is up. */
    suspend fun connect(ctx: Context): Boolean {
        if (isUp(ctx)) return true
        if (!installed(ctx)) return false
        ctx.sendBroadcast(Intent("$PACKAGE.CONNECT_VPN").setClassName(PACKAGE, RECEIVER))
        val up = withTimeoutOrNull(15_000) {
            while (!isUp(ctx)) delay(400)
            true
        } == true
        if (up) prefs(ctx).edit().putBoolean(OURS, true).apply()
        return up
    }

    /** Turns the VPN off again, only if this app turned it on. */
    fun disconnectIfOurs(ctx: Context) {
        if (!ours(ctx)) return
        prefs(ctx).edit().putBoolean(OURS, false).apply()
        if (isUp(ctx)) ctx.sendBroadcast(Intent("$PACKAGE.DISCONNECT_VPN").setClassName(PACKAGE, RECEIVER))
    }

    /** Tailscale is not installed: its store page. */
    fun openStore(ctx: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
