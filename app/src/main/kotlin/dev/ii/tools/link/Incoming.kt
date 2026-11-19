package dev.ii.tools.link

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A file the PC is sending (PROTOCOL.md, "Files", the other way): a `file` header, then
 * binary frames, written to the cache as they come so nothing large sits in memory.
 * One at a time; a new header drops an unfinished one.
 */
internal class Incoming(dir: File, header: JSONObject) {
    val id: Int = header.optInt("id")
    val name: String = header.optString("name").substringAfterLast('/').ifEmpty { "file" }
    val purpose: String = header.optString("purpose")
    private val size: Long = header.optLong("size")
    val file: File = File(dir.apply { mkdirs() }, "in-$id")
    private val out = FileOutputStream(file)
    private var received = 0L

    val valid: Boolean = size in 1..(64L shl 20)

    /** Writes a chunk; true once the file is whole. */
    fun write(bytes: ByteArray): Boolean {
        received += bytes.size
        if (received > size) throw IllegalStateException("more than announced")
        out.write(bytes)
        if (received < size) return false
        out.close()
        return true
    }

    fun discard() {
        runCatching { out.close() }
        file.delete()
    }
}
