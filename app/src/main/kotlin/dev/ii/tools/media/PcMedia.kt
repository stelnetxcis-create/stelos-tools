// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: shrimqy and the Sefirah contributors (sefirah.media.RemotePlaybackFeature)
// SPDX-FileContributor: ii tools: the framework MediaSession and MediaStyle instead of
//   AndroidX media compat, one PC player fed by the ii link topic `pcMedia`, the cover
//   received once as a file, no remote volume.

package dev.ii.tools.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.ii.tools.MainActivity
import dev.ii.tools.R
import dev.ii.tools.audio.VolumeKeys
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** The PC's player as the app's own screens show it; [position] was read at [at] (elapsed realtime). */
data class PcPlayer(
    val title: String,
    val artist: String,
    val app: String,
    val playing: Boolean,
    val position: Long,
    val length: Long,
    val rate: Float,
    val at: Long,
    val art: Bitmap?,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val canSeek: Boolean,
    val url: String = "",
    val track: String = "",
) {
    /** Where the track is at [now] (elapsed realtime). */
    fun positionAt(now: Long): Long {
        if (!playing) return position
        val p = position + ((now - at) * rate).toLong()
        return if (length > 0) p.coerceIn(0, length) else p
    }
}

/**
 * What plays on the PC, as a media player on the phone (PROTOCOL.md, "The PC's media"):
 * a media session the system draws on the lock screen and in Quick Settings, and a
 * media notification for it. The shell pushes the player whole (`pcMedia`); the
 * position is extrapolated here from the playback state, so nothing ticks over the link.
 */
object PcMedia {
    private const val PREFS = "pcMedia"
    private const val ENABLED = "enabled"
    private const val CHANNEL = "pc_media"
    private const val NOTIF_ID = 0x6d65
    private const val ART_MAX = 512
    private const val ART_KEEP = 4

    private val main = Handler(Looper.getMainLooper())
    private val localAudioAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private var app: Context? = null
    private var session: MediaSession? = null
    private var remoteProvider: VolumeProvider? = null
    /** The player as last pushed, `position` read at [shownAt] (elapsed realtime). */
    private var current: JSONObject? = null
    private var shownAt = 0L
    private var art: Bitmap? = null
    private var artName = ""

    private val _player = MutableStateFlow<PcPlayer?>(null)
    /** The same player for the home's media card; null when nothing plays. */
    val player: StateFlow<PcPlayer?> = _player.asStateFlow()

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        if (!on) clear()
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> =
        if (isEnabled(c)) listOf("pcMedia", "pcLyrics") else emptyList()

    /** The `pcMedia` topic, from the socket thread. */
    fun update(c: Context, m: JSONObject) {
        val ctx = c.applicationContext
        main.post {
            app = ctx
            if (!isEnabled(ctx) || !m.optBoolean("on") || m.optString("title").isEmpty()) {
                clear()
                return@post
            }
            current = m
            shownAt = SystemClock.elapsedRealtime()
            val name = m.optString("art")
            if (name != artName) {
                artName = name
                art = loadArt(ctx, name)
            }
            show(ctx)
        }
    }

    /** A cover the shell sent (purpose `pcMediaArt`, named by its hash). */
    fun artReceived(c: Context, file: File, name: String): Boolean {
        val dir = artDir(c)
        val target = File(dir, name.filter { it.isLetterOrDigit() }.ifEmpty { return false })
        if (!file.renameTo(target) && runCatching { file.copyTo(target, overwrite = true) }.isFailure) return false
        // A few covers back, for a player skipping between tracks.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(ART_KEEP)?.forEach { it.delete() }
        val ctx = c.applicationContext
        main.post {
            if (current != null && target.name == artName) {
                art = loadArt(ctx, artName)
                show(ctx)
            }
        }
        return true
    }

    /** The link went: there is no player to control any more. */
    fun clear() {
        main.post {
            current = null
            _player.value = null
            PcLyrics.onPlayerCleared()
            remoteProvider = null
            session?.let {
                it.isActive = false
                it.release()
            }
            session = null
            app?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            VolumeKeys.onPcMediaChanged()
        }
    }

    internal fun isSessionPlaying(): Boolean =
        session?.isActive == true && current?.optBoolean("playing") == true

    internal fun hasRemoteProvider(): Boolean = remoteProvider != null

    internal fun attachVolumeProvider(provider: VolumeProvider) {
        val s = session ?: return
        if (remoteProvider === provider) return
        remoteProvider = provider
        s.setPlaybackToRemote(provider)
    }

    internal fun detachVolumeProvider() {
        val s = session
        if (remoteProvider == null) return
        remoteProvider = null
        s?.setPlaybackToLocal(localAudioAttrs)
    }

    /** A button on the phone (session callback or the notification's actions). */
    fun command(action: String, value: Long? = null) {
        val m = current ?: return
        val arg = JSONObject().put("a", action)
        if (value != null) arg.put("value", value)
        LinkHub.send("pcMedia", arg)
        // Shown at once; the shell's push confirms.
        when (action) {
            "play", "pause", "toggle" -> {
                val playing = if (action == "toggle") !m.optBoolean("playing") else action == "play"
                current = JSONObject(m.toString()).put("playing", playing).put("position", position())
                shownAt = SystemClock.elapsedRealtime()
            }
            "seek" -> {
                current = JSONObject(m.toString()).put("position", value ?: 0L)
                shownAt = SystemClock.elapsedRealtime()
            }
            else -> return
        }
        app?.let(::show)
    }

    /** The page of what plays and where it is now, for a handoff; null when the player has no web page. */
    fun handoffTarget(): Pair<String, Long>? {
        val url = current?.optString("url").orEmpty()
        return if (url.startsWith("http")) url to position() else null
    }

    /** Where the track is now, from the last push and the time since. */
    private fun position(): Long {
        val m = current ?: return 0
        val base = m.optLong("position")
        if (!m.optBoolean("playing")) return base
        val now = base + ((SystemClock.elapsedRealtime() - shownAt) * m.optDouble("rate", 1.0)).toLong()
        val length = m.optLong("length")
        return if (length > 0) now.coerceAtMost(length) else now
    }

    private fun show(ctx: Context) {
        val m = current ?: return
        val playing = m.optBoolean("playing")
        val track = m.optString("track")
        _player.value = PcPlayer(
            title = m.optString("title"),
            artist = m.optString("artist"),
            app = m.optString("app"),
            playing = playing,
            position = m.optLong("position"),
            length = m.optLong("length"),
            rate = m.optDouble("rate", 1.0).toFloat(),
            at = shownAt,
            art = art,
            canNext = m.optBoolean("canNext"),
            canPrevious = m.optBoolean("canPrevious"),
            canSeek = m.optBoolean("canSeek"),
            url = m.optString("url"),
            track = track,
        )
        PcLyrics.onTrackChanged(track)
        val s = session ?: MediaSession(ctx, "ii tools PC").also {
            it.setCallback(callback, main)
            session = it
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, m.optString("title"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, m.optString("artist"))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, m.optString("album"))
                .apply {
                    val length = m.optLong("length")
                    if (length > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, length)
                    art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                }
                .build(),
        )
        var actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
        if (m.optBoolean("canNext")) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (m.optBoolean("canPrevious")) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (m.optBoolean("canSeek")) actions = actions or PlaybackState.ACTION_SEEK_TO
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    m.optLong("position"),
                    if (playing) m.optDouble("rate", 1.0).toFloat() else 0f,
                    shownAt,
                )
                .build(),
        )
        s.isActive = true
        VolumeKeys.onPcMediaChanged()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.pcmedia_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val buttons = buildList {
            if (m.optBoolean("canPrevious")) add(button(ctx, R.drawable.rounded_skip_previous, R.string.pcmedia_previous, "previous"))
            add(
                if (playing) button(ctx, R.drawable.rounded_pause, R.string.pcmedia_pause, "pause")
                else button(ctx, R.drawable.rounded_play_arrow, R.string.pcmedia_play, "play"),
            )
            if (m.optBoolean("canNext")) add(button(ctx, R.drawable.rounded_skip_next, R.string.pcmedia_next, "next"))
        }
        val handoffUrl = m.optString("url")
        val handoff = if (Handoff.isEnabled(ctx) && handoffUrl.startsWith("http")) {
            Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.rounded_smartphone),
                ctx.getString(R.string.handoff_action),
                PendingIntent.getActivity(
                    ctx, 1,
                    Intent(ctx, HandoffActivity::class.java).putExtra("url", handoffUrl).putExtra("position", position()),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()
        } else null
        val compact = IntArray(buttons.size) { it }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.rounded_desktop_windows)
            .setContentTitle(m.optString("title"))
            .setContentText(m.optString("artist"))
            .setSubText(listOf(m.optString("app"), LinkHub.pc.value?.name.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "))
            .setLargeIcon(art)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(*compact))
            .apply {
                buttons.forEach(::addAction)
                handoff?.let(::addAction)
            }
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    private fun button(ctx: Context, icon: Int, label: Int, action: String): Notification.Action {
        val intent = PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, PcMediaAction::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(label), intent).build()
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = command("play")
        override fun onPause() = command("pause")
        override fun onSkipToNext() = command("next")
        override fun onSkipToPrevious() = command("previous")
        override fun onSeekTo(pos: Long) = command("seek", pos)
    }

    private fun artDir(c: Context) = File(c.cacheDir, "pcmedia").apply { mkdirs() }

    private fun loadArt(c: Context, name: String): Bitmap? {
        if (name.isEmpty()) return null
        val f = File(artDir(c), name)
        if (!f.isFile) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / ART_MAX).coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }
}

/** The notification's buttons. */
class PcMediaAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.action?.let { PcMedia.command(it) }
    }
}
