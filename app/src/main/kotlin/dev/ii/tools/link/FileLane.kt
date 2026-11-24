package dev.ii.tools.link

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * *Faster* file transfers (PROTOCOL.md, "Files", *Fast lane*): a file's bytes on a TLS
 * socket of their own instead of the link's WebSocket.
 *
 * Over the WebSocket every byte the phone sends is masked one at a time (RFC 6455) and
 * queued behind the link's messages in 256 KiB frames; that, not the Wi-Fi, set the speed.
 * The lane is a plain TLS stream (Conscrypt's AES, in hardware) on the link's own port,
 * under the same pinned certificate, carrying 1 MiB blocks as they are: the network's
 * speed, and files up to [MAX_SIZE]. *Safe* keeps everything on the link's socket.
 *
 * Small files (under [MIN_SIZE]) stay on the link either way, where a second handshake
 * would cost more than it saves; a lane that cannot be opened falls back to the link.
 */
object FileLane {
    private const val TAG = "IiLink"
    private const val PREFS = "link"
    private const val KEY = "fastFiles"
    const val MIN_SIZE = 1L shl 20
    const val MAX_SIZE = 16L shl 30
    /** What the link's socket takes (the helper's `files::MAX_SIZE`). */
    const val LINK_MAX = 64L shl 20
    private const val BLOCK = 1 shl 20
    private val MAGIC = "IIL1".toByteArray(Charsets.US_ASCII)

    private val _fast = MutableStateFlow<Boolean?>(null)
    val fast: StateFlow<Boolean?> = _fast

    fun isFast(ctx: Context): Boolean =
        _fast.value ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false).also { _fast.value = it }

    fun setFast(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        _fast.value = on
        // The PCs send their files over a lane only to a phone that says it takes them.
        LinkHub.announceCaps()
    }

    fun caps(ctx: Context): List<String> = if (isFast(ctx)) listOf("fileLane") else emptyList()

    enum class End { DONE, UNREACHED, BROKEN, READ }

    /** The lane for [token]: the magic in the clear, TLS pinned to [fingerprint], the token. */
    private fun open(host: String, port: Int, ssl: SSLSocketFactory, token: String): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), 4_000)
            // Twenty seconds without a byte: the phone walked out of the Wi-Fi.
            raw.soTimeout = 20_000
            raw.getOutputStream().apply { write(MAGIC); flush() }
            val tls = ssl.createSocket(raw, host, port, true) as SSLSocket
            tls.startHandshake()
            tls.outputStream.apply { write(hex(token)); flush() }
            return tls
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    /** Sends [size] bytes from [open] on the lane [token] opens. */
    fun upload(
        host: String,
        port: Int,
        ssl: SSLSocketFactory,
        token: String,
        size: Long,
        open: () -> InputStream?,
        onProgress: (Float) -> Unit,
    ): End {
        val sock = runCatching { open(host, port, ssl, token) }.getOrElse { e ->
            android.util.Log.w(TAG, "lane to $host:$port unreachable", e)
            return End.UNREACHED
        }
        return sock.use {
            val input = runCatching { open() }.getOrNull() ?: return End.READ
            input.use { stream ->
                val out = sock.outputStream
                val buf = ByteArray(BLOCK)
                var sent = 0L
                while (sent < size) {
                    // A full block per write: one TLS flight, fewer system calls.
                    var filled = 0
                    val want = minOf(BLOCK.toLong(), size - sent).toInt()
                    while (filled < want) {
                        val n = runCatching { stream.read(buf, filled, want - filled) }.getOrElse { return End.READ }
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled == 0) return End.READ
                    runCatching { out.write(buf, 0, filled) }.getOrElse { e ->
                        android.util.Log.w(TAG, "lane broke after $sent of $size bytes", e)
                        return End.BROKEN
                    }
                    sent += filled
                    onProgress(sent.toFloat() / size)
                }
                runCatching { out.flush() }.getOrElse { return End.BROKEN }
                // Close only once the PC says the file is on disk: closing with its TLS
                // session ticket unread resets the socket, and the PC loses the tail.
                val landed = runCatching { sock.inputStream.read() }.getOrDefault(-1)
                if (landed < 0) End.BROKEN else End.DONE
            }
        }
    }

    /** Receives [inc] on the lane [token] opens. */
    internal fun download(host: String, port: Int, ssl: SSLSocketFactory, token: String, inc: Incoming): End {
        val sock = runCatching { open(host, port, ssl, token) }.getOrElse { e ->
            android.util.Log.w(TAG, "lane from $host:$port unreachable", e)
            return End.UNREACHED
        }
        return sock.use {
            val input = sock.inputStream
            val buf = ByteArray(BLOCK)
            var whole = false
            while (!whole) {
                val n = runCatching { input.read(buf) }.getOrElse { return End.BROKEN }
                if (n < 0) return End.BROKEN
                whole = runCatching { inc.write(buf, n) }.getOrElse { return End.BROKEN }
            }
            End.DONE
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
