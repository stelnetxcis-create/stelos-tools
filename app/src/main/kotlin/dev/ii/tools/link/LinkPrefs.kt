package dev.ii.tools.link

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** What a pairing QR code (or `iilink://pair` link) carries. */
data class PairingUri(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val secret: String,
    val pcName: String,
) {
    companion object {
        fun parse(uri: Uri?): PairingUri? {
            if (uri == null || uri.scheme != "iilink" || uri.host != "pair") return null
            if (uri.getQueryParameter("v") != "1") return null
            val hosts = uri.getQueryParameter("h").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
            val fp = uri.getQueryParameter("fp")?.lowercase() ?: return null
            val secret = uri.getQueryParameter("s") ?: return null
            if (hosts.isEmpty() || fp.length != 64 || port !in 1..65535) return null
            return PairingUri(hosts, port, fp, secret, uri.getQueryParameter("n").orEmpty())
        }

        fun parse(text: String?): PairingUri? = text?.let { parse(Uri.parse(it.trim())) }
    }
}

/** A PC this phone is paired with. */
data class PairedPc(
    val id: String,
    val token: String,
    val fingerprint: String,
    /** Last address that answered first, then the ones from the QR code. */
    val hosts: List<String>,
    val port: Int,
    val name: String,
    /** Its Tailscale addresses, tried away from its LAN (the shell's `hello.routes`). */
    val tailnet: List<String> = emptyList(),
)

/**
 * The paired PCs and the one the home controls, in app-private storage (backups are off
 * for the whole app). A PC is known by its certificate's fingerprint: pairing it again
 * replaces its entry.
 */
object LinkPrefs {
    private const val FILE = "link"
    private const val PCS = "pcs"
    private const val SELECTED = "selected"

    fun load(ctx: Context): List<PairedPc> {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        migrate(ctx)
        val arr = runCatching { JSONArray(sp.getString(PCS, "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifEmpty { null } ?: continue
                val token = o.optString("token").ifEmpty { null } ?: continue
                val fp = o.optString("fp").ifEmpty { null } ?: continue
                add(
                    PairedPc(
                        id = id,
                        token = token,
                        fingerprint = fp,
                        hosts = o.optString("hosts").split(',').filter { it.isNotEmpty() && !Tailnet.isTailnet(it) },
                        port = o.optInt("port", 47210),
                        name = o.optString("name"),
                        // A QR code made with Tailscale up carried the tailnet address among the hosts.
                        tailnet = (o.optString("tailnet").split(',') + o.optString("hosts").split(','))
                            .filter { Tailnet.isTailnet(it) }.distinct(),
                    ),
                )
            }
        }.distinctBy { it.fingerprint }
    }

    fun save(ctx: Context, pcs: List<PairedPc>) {
        val arr = JSONArray()
        pcs.forEach { pc ->
            arr.put(
                JSONObject()
                    .put("id", pc.id)
                    .put("token", pc.token)
                    .put("fp", pc.fingerprint)
                    .put("hosts", pc.hosts.joinToString(","))
                    .put("port", pc.port)
                    .put("name", pc.name)
                    .put("tailnet", pc.tailnet.joinToString(",")),
            )
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(PCS, arr.toString()).apply()
    }

    fun selected(ctx: Context): String? = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(SELECTED, null)

    fun select(ctx: Context, fingerprint: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(SELECTED, fingerprint).apply()
    }

    /** Up to 1.2.0 one PC lived in flat keys of the same file. */
    private fun migrate(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val id = sp.getString("id", null) ?: return
        val token = sp.getString("token", null)
        val fp = sp.getString("fp", null)
        val edit = sp.edit()
        if (token != null && fp != null && !sp.contains(PCS)) {
            val pc = PairedPc(
                id = id,
                token = token,
                fingerprint = fp,
                hosts = sp.getString("hosts", "").orEmpty().split(',').filter { it.isNotEmpty() },
                port = sp.getInt("port", 47210),
                name = sp.getString("name", "").orEmpty(),
            )
            save(ctx, listOf(pc))
            edit.putString(SELECTED, fp)
            sp.getString("wake", null)?.let { edit.putString("wake:$fp", it) }
        }
        listOf("id", "token", "fp", "hosts", "port", "name", "wake").forEach { edit.remove(it) }
        edit.apply()
    }
}
