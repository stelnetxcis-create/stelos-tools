package dev.ii.tools.gallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

/**
 * The phone's photos and videos for the PC, read only (CONTRACT.md, `gallery`).
 *
 * Queries run on [Dispatchers.IO]. Thumbnails and full-image transfers share a single
 * worker so `get` goes ahead of queued thumbnails (after the file currently in flight),
 * while [LinkHub.upload] serializes transfers across all features. A [ContentObserver]
 * watches for changes only while the link is online and both sides advertise `gallery`.
 */
object Gallery {
    private const val PREFS = "gallery"
    private const val ENABLED = "enabled"
    private const val MAX_THUMBS = 128
    private const val MAX_FULL_BYTES = 64L * 1024L * 1024L
    private const val THUMB_SIZE = 256
    private const val OBSERVER_DEBOUNCE_MS = 1_500L

    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")
    private const val TAKEN_EXPR =
        "(CASE WHEN ${MediaStore.MediaColumns.DATE_TAKEN} > 0 THEN ${MediaStore.MediaColumns.DATE_TAKEN} ELSE ${MediaStore.MediaColumns.DATE_MODIFIED} * 1000 END)"
    private const val SORT_ORDER = "$TAKEN_EXPR DESC, ${MediaStore.Files.FileColumns._ID} DESC"

    private val ITEM_PROJECTION = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.ORIENTATION,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DATE_ADDED,
    )

    val PERMISSIONS: Array<String> = buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }.toTypedArray()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Preferences & permissions ─────────────────────────────────────────

    fun isEnabled(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        val app = c.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        if (!on) clearQueues()
        syncObserver(app)
        LinkHub.announceCaps()
    }

    fun canReadImages(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 &&
                c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    fun canReadVideos(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    /** True when the setup step is complete (full image+video, or intentional partial selection). */
    fun hasPermission(c: Context): Boolean =
        if (c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
            canReadVideos(c)
        } else {
            Build.VERSION.SDK_INT >= 34 &&
                c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        }

    fun caps(c: Context): List<String> =
        if (isEnabled(c) && canReadImages(c)) listOf("gallery") else emptyList()

    fun onPermissionChanged(c: Context) {
        val app = c.applicationContext
        syncObserver(app)
        LinkHub.announceCaps()
    }

    // ── Incoming messages ─────────────────────────────────────────────────

    fun onMessage(c: Context, m: JSONObject) {
        val ctx = c.applicationContext
        if (!isEnabled(ctx)) {
            sendErr("disabled")
            return
        }
        if (!canReadImages(ctx)) {
            sendErr("permission")
            return
        }
        when (m.optString("a")) {
            "albums" -> scope.launch { handleAlbums(ctx) }
            "list" -> scope.launch { handleList(ctx, m) }
            "thumbs" -> enqueueThumbs(ctx, m.optJSONArray("ids"))
            "get" -> {
                val id = m.optLong("id", -1L)
                if (id >= 0L) enqueueGet(ctx, id)
            }
        }
    }

    private fun sendErr(code: String) {
        LinkHub.sendJson(JSONObject().put("t", "gallery").put("err", code))
    }

    private fun sendGetErr(id: Long, code: String) {
        LinkHub.sendJson(JSONObject().put("t", "gallery").put("a", "get").put("id", id).put("err", code))
    }

    private fun sendGone(ids: List<Long>) {
        if (ids.isEmpty()) return
        LinkHub.sendJson(JSONObject().put("t", "gallery").put("a", "gone").put("ids", JSONArray(ids)))
    }

    // ── Albums & paginated list ───────────────────────────────────────────

    private class AlbumAcc(val id: String, var name: String, val cover: Long, var count: Int = 1)

    private fun handleAlbums(ctx: Context) {
        if (!isEnabled(ctx) || !canReadImages(ctx)) return
        val albums = LinkedHashMap<String, AlbumAcc>()
        val (sel, selArgs) = mediaTypeFilter(canReadVideos(ctx))
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT_ORDER)
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            ctx.contentResolver.query(FILES_URI, projection, args, null)?.use { cur ->
                val idxId = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val idxBucketId = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val idxBucketName = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cur.moveToNext()) {
                    val bucketId = cur.getString(idxBucketId) ?: continue
                    val bucketName = cur.getString(idxBucketName).orEmpty()
                    val existing = albums[bucketId]
                    if (existing == null) {
                        albums[bucketId] = AlbumAcc(bucketId, bucketName, cur.getLong(idxId))
                    } else {
                        existing.count++
                        if (existing.name.isEmpty() && bucketName.isNotEmpty()) existing.name = bucketName
                    }
                }
            }
        }
        val list = JSONArray()
        for (a in albums.values) {
            list.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("count", a.count)
                    .put("cover", a.cover),
            )
        }
        LinkHub.sendJson(JSONObject().put("t", "gallery").put("a", "albums").put("list", list))
    }

    private fun handleList(ctx: Context, m: JSONObject) {
        if (!isEnabled(ctx) || !canReadImages(ctx)) return
        val album = m.optString("album", "")
        val n = m.optInt("n", 200).coerceIn(1, 500)
        val before = m.optJSONObject("before")

        val (typeSel, typeArgs) = mediaTypeFilter(canReadVideos(ctx))
        val clauses = ArrayList<String>(3).apply { add(typeSel) }
        val selArgs = ArrayList<String>(6).apply { addAll(typeArgs) }
        if (album.isNotEmpty()) {
            clauses.add("${MediaStore.MediaColumns.BUCKET_ID} = ?")
            selArgs.add(album)
        }
        if (before != null) {
            val beforeTaken = before.optLong("taken")
            val beforeId = before.optLong("id")
            clauses.add("($TAKEN_EXPR < ? OR ($TAKEN_EXPR = ? AND ${MediaStore.Files.FileColumns._ID} < ?))")
            selArgs.add(beforeTaken.toString())
            selArgs.add(beforeTaken.toString())
            selArgs.add(beforeId.toString())
        }

        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, clauses.joinToString(" AND "))
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selArgs.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT_ORDER)
            putInt(ContentResolver.QUERY_ARG_LIMIT, n + 1)
        }

        val items = JSONArray()
        var more = false
        runCatching {
            ctx.contentResolver.query(FILES_URI, ITEM_PROJECTION, args, null)?.use { cur ->
                val cols = ItemCols(cur)
                while (cur.moveToNext()) {
                    if (items.length() >= n) {
                        more = true
                        break
                    }
                    items.put(readItem(cur, cols))
                }
            }
        }

        val reply = JSONObject()
            .put("t", "gallery")
            .put("a", "list")
            .put("album", album)
            .put("items", items)
            .put("more", more)
        if (m.has("req")) reply.put("req", m.opt("req"))
        LinkHub.sendJson(reply)
    }

    private class ItemCols(cur: android.database.Cursor) {
        val id = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val mediaType = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val name = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mime = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val width = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val height = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val orientation = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
        val size = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val dateTaken = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateMod = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val duration = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val bucketId = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        val data = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        val dateAdded = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
    }

    private fun readItem(cur: android.database.Cursor, c: ItemCols): JSONObject {
        val isVideo = cur.getInt(c.mediaType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val rawW = cur.getInt(c.width)
        val rawH = cur.getInt(c.height)
        val orient = cur.getInt(c.orientation)
        val swap = orient == 90 || orient == 270
        val dateTaken = cur.getLong(c.dateTaken)
        val taken = if (dateTaken > 0L) dateTaken else cur.getLong(c.dateMod) * 1000L
        return JSONObject()
            .put("id", cur.getLong(c.id))
            .put("kind", if (isVideo) "video" else "image")
            .put("name", cur.getString(c.name).orEmpty())
            .put("mime", cur.getString(c.mime).orEmpty())
            .put("w", if (swap) rawH else rawW)
            .put("h", if (swap) rawW else rawH)
            .put("size", cur.getLong(c.size))
            .put("taken", taken)
            .put("dur", if (isVideo) cur.getLong(c.duration) else 0L)
            .put("album", cur.getString(c.bucketId).orEmpty())
            .put("path", cur.getString(c.data).orEmpty())
    }

    private fun mediaTypeFilter(includeVideo: Boolean): Pair<String, Array<String>> =
        if (includeVideo) {
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)" to
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                )
        } else {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?" to
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
        }

    // ── Thumbnail & full-image worker ─────────────────────────────────────

    private val queueLock = Any()
    private val getQueue = ArrayDeque<Long>()
    private val thumbQueue = ArrayDeque<Long>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var workerCtx: Context? = null

    init {
        scope.launch {
            while (true) {
                signal.receive()
                while (true) {
                    val ctx = workerCtx ?: break
                    val nextGet: Long?
                    val nextThumb: Long?
                    synchronized(queueLock) {
                        nextGet = getQueue.removeFirstOrNull()
                        nextThumb = if (nextGet == null) thumbQueue.removeFirstOrNull() else null
                    }
                    if (nextGet == null && nextThumb == null) break
                    if (!LinkHub.isConnected() || !isEnabled(ctx) || !canReadImages(ctx)) {
                        clearQueues()
                        break
                    }
                    if (nextGet != null) processGet(ctx, nextGet)
                    else if (nextThumb != null) processThumb(ctx, nextThumb)
                }
            }
        }
    }

    private fun enqueueThumbs(ctx: Context, ids: JSONArray?) {
        workerCtx = ctx
        synchronized(queueLock) {
            thumbQueue.clear()
            if (ids != null) {
                val seen = HashSet<Long>()
                for (i in 0 until ids.length()) {
                    if (thumbQueue.size >= MAX_THUMBS) break
                    val id = ids.optLong(i, -1L)
                    if (id >= 0L && seen.add(id)) thumbQueue.addLast(id)
                }
            }
        }
        signal.trySend(Unit)
    }

    private fun enqueueGet(ctx: Context, id: Long) {
        workerCtx = ctx
        synchronized(queueLock) {
            if (id !in getQueue) getQueue.addLast(id)
        }
        signal.trySend(Unit)
    }

    private fun clearQueues() {
        synchronized(queueLock) {
            getQueue.clear()
            thumbQueue.clear()
        }
    }

    private data class ItemRef(val mediaType: Int, val mime: String, val size: Long)

    private fun lookupItem(ctx: Context, id: Long): ItemRef? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns._ID} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
        )
        return runCatching {
            ctx.contentResolver.query(FILES_URI, projection, args, null)?.use { cur ->
                if (!cur.moveToFirst()) null
                else ItemRef(cur.getInt(0), cur.getString(1).orEmpty(), cur.getLong(2))
            }
        }.getOrNull()
    }

    private suspend fun processThumb(ctx: Context, id: Long) {
        val ref = lookupItem(ctx, id)
        val uri = when {
            ref == null -> null
            ref.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            ref.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO && canReadVideos(ctx) ->
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            else -> null
        }
        if (uri == null) {
            sendGone(listOf(id))
            return
        }
        val bytes = try {
            val bmp = ctx.contentResolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), null)
            try {
                ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.JPEG, 80, this) }.toByteArray()
            } finally {
                bmp.recycle()
            }
        } catch (_: FileNotFoundException) {
            sendGone(listOf(id))
            return
        } catch (_: Throwable) {
            return
        }
        LinkHub.upload("$id.jpg", bytes.size.toLong(), { bytes.inputStream() }, "galleryThumb", "", waitForLink = false) { }
    }

    private suspend fun processGet(ctx: Context, id: Long) {
        val ref = lookupItem(ctx, id)
        if (ref == null) {
            sendGetErr(id, "not_found")
            return
        }
        if (ref.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
            sendGetErr(id, if (canReadVideos(ctx)) "video" else "not_found")
            return
        }
        if (ref.mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
            sendGetErr(id, "not_found")
            return
        }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val mime = ref.mime.ifEmpty { ctx.contentResolver.getType(uri).orEmpty() }.lowercase()
        val ext = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
        if (ext != null) {
            val size = try {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0L } ?: ref.size
            } catch (_: FileNotFoundException) {
                sendGetErr(id, "not_found")
                return
            } catch (_: Throwable) {
                sendGetErr(id, "read")
                return
            }
            if (size <= 0L) {
                sendGetErr(id, "read")
                return
            }
            if (size > MAX_FULL_BYTES) {
                sendGetErr(id, "too_large")
                return
            }
            val why = LinkHub.upload("$id.$ext", size, { ctx.contentResolver.openInputStream(uri) }, "galleryFull", "", waitForLink = false) { }
            if (why == "too_large" || why == "read") sendGetErr(id, why)
            return
        }

        // Anything else (HEIC/HEIF/AVIF/DNG…): decode honoring orientation, write JPEG 92 to temp, then stream.
        val tmp = runCatching { File.createTempFile("gallery_", ".jpg", ctx.cacheDir) }.getOrElse {
            sendGetErr(id, "read")
            return
        }
        try {
            val bmp = try {
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: FileNotFoundException) {
                sendGetErr(id, "not_found")
                return
            } catch (_: Throwable) {
                sendGetErr(id, "read")
                return
            }
            val compressed = try {
                tmp.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } catch (_: Throwable) {
                false
            } finally {
                bmp.recycle()
            }
            if (!compressed) {
                sendGetErr(id, "read")
                return
            }
            val len = tmp.length()
            if (len > MAX_FULL_BYTES) {
                sendGetErr(id, "too_large")
                return
            }
            val why = LinkHub.upload("$id.jpg", len, { tmp.inputStream() }, "galleryFull", "", waitForLink = false) { }
            if (why == "too_large" || why == "read") sendGetErr(id, why)
        } finally {
            tmp.delete()
        }
    }

    // ── Live ContentObserver ──────────────────────────────────────────────

    private val obsLock = Any()
    private val flushMutex = Mutex()
    private var observer: ContentObserver? = null
    private var observedVideos = false
    private var debounceJob: Job? = null
    private var baselineReady: CompletableDeferred<Unit>? = null
    private var lastDateAdded = 0L
    private val seenAtMaxDate = HashSet<Long>()
    private val pendingDeleted = LinkedHashSet<Long>()
    private var pendingNonDelete = false
    private var pendingChanged = false

    private fun isObserverWanted(ctx: Context): Boolean =
        LinkHub.isConnected() &&
            LinkHub.anyHas("gallery") &&
            isEnabled(ctx) &&
            canReadImages(ctx)

    fun syncObserver(c: Context) {
        val ctx = c.applicationContext
        val want = isObserverWanted(ctx)
        val wantVideo = canReadVideos(ctx)
        synchronized(obsLock) {
            val current = observer
            if (!want) {
                if (current != null) unregisterLocked(ctx, current)
                return
            }
            if (current != null && observedVideos == wantVideo) return
            if (current != null) unregisterLocked(ctx, current)

            val ready = CompletableDeferred<Unit>()
            baselineReady = ready
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                    onMediaChange(ctx, uris, flags)
                }
            }
            ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
            if (wantVideo) {
                ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
            }
            observer = obs
            observedVideos = wantVideo
            scope.launch { initBaseline(ctx, ready) }
        }
    }

    fun onLinkLost(c: Context) {
        clearQueues()
        syncObserver(c)
    }

    private fun unregisterLocked(ctx: Context, current: ContentObserver) {
        runCatching { ctx.contentResolver.unregisterContentObserver(current) }
        observer = null
        observedVideos = false
        debounceJob?.cancel()
        debounceJob = null
        baselineReady?.complete(Unit)
        baselineReady = null
        pendingDeleted.clear()
        pendingNonDelete = false
        pendingChanged = false
    }

    private fun onMediaChange(ctx: Context, uris: Collection<Uri>, flags: Int) {
        synchronized(obsLock) {
            if (observer == null) return
            if ((flags and ContentResolver.NOTIFY_DELETE) != 0) {
                var gotId = false
                for (u in uris) {
                    val id = u.lastPathSegment?.toLongOrNull()
                    if (id != null) {
                        pendingDeleted.add(id)
                        gotId = true
                    }
                }
                if (!gotId) pendingChanged = true
            } else {
                pendingNonDelete = true
                if (uris.isEmpty()) pendingChanged = true
            }
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(OBSERVER_DEBOUNCE_MS)
                flushObserver(ctx)
            }
        }
    }

    private suspend fun initBaseline(ctx: Context, ready: CompletableDeferred<Unit>) {
        flushMutex.withLock {
            try {
                val (sel, selArgs) = mediaTypeFilter(canReadVideos(ctx))
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selArgs)
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC",
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 64)
                }
                var maxDate = 0L
                val ids = HashSet<Long>()
                runCatching {
                    ctx.contentResolver.query(
                        FILES_URI,
                        arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                        args,
                        null,
                    )?.use { cur ->
                        if (cur.moveToFirst()) {
                            maxDate = cur.getLong(1)
                            do {
                                if (cur.getLong(1) != maxDate) break
                                ids.add(cur.getLong(0))
                            } while (cur.moveToNext())
                        }
                    }
                }
                lastDateAdded = maxDate
                seenAtMaxDate.clear()
                seenAtMaxDate.addAll(ids)
            } finally {
                ready.complete(Unit)
            }
        }
    }

    private suspend fun flushObserver(ctx: Context) {
        val ready = synchronized(obsLock) { baselineReady }
        ready?.await()
        flushMutex.withLock {
            if (!isObserverWanted(ctx)) return
            val deleted: List<Long>
            val checkNew: Boolean
            val unknownChange: Boolean
            synchronized(obsLock) {
                deleted = pendingDeleted.toList()
                pendingDeleted.clear()
                checkNew = pendingNonDelete
                pendingNonDelete = false
                unknownChange = pendingChanged
                pendingChanged = false
            }
            if (deleted.isNotEmpty()) sendGone(deleted)
            var sentNew = false
            if (checkNew) {
                val items = queryNewItems(ctx)
                if (items.length() > 0) {
                    LinkHub.sendJson(JSONObject().put("t", "gallery").put("a", "new").put("items", items))
                    sentNew = true
                }
            }
            if (unknownChange || (checkNew && !sentNew && deleted.isEmpty())) {
                LinkHub.sendJson(JSONObject().put("t", "gallery").put("a", "changed"))
            }
        }
    }

    private fun queryNewItems(ctx: Context): JSONArray {
        val (typeSel, typeArgs) = mediaTypeFilter(canReadVideos(ctx))
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "$typeSel AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, typeArgs + lastDateAdded.toString())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT_ORDER)
        }
        val items = JSONArray()
        var newMaxDate = lastDateAdded
        val idsAtNewMax = HashSet<Long>()
        runCatching {
            ctx.contentResolver.query(FILES_URI, ITEM_PROJECTION, args, null)?.use { cur ->
                val cols = ItemCols(cur)
                while (cur.moveToNext()) {
                    val id = cur.getLong(cols.id)
                    val added = cur.getLong(cols.dateAdded)
                    if (added == lastDateAdded && id in seenAtMaxDate) continue
                    items.put(readItem(cur, cols))
                    if (added > newMaxDate) {
                        newMaxDate = added
                        idsAtNewMax.clear()
                        idsAtNewMax.add(id)
                    } else if (added == newMaxDate) {
                        idsAtNewMax.add(id)
                    }
                }
            }
        }
        if (newMaxDate > lastDateAdded) {
            lastDateAdded = newMaxDate
            seenAtMaxDate.clear()
            seenAtMaxDate.addAll(idsAtNewMax)
        } else if (idsAtNewMax.isNotEmpty()) {
            seenAtMaxDate.addAll(idsAtNewMax)
        }
        return items
    }
}
