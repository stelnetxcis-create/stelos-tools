// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: shrimqy and the Sefirah contributors (sefirah.media.PlaybackFeature, MediaSession)
// SPDX-FileContributor: ii tools: one object instead of Hilt-injected features, the ii link
//   protocol (`media`, `mediaGone`, `mediaCmd`) instead of Sefirah's messages, cover sent
//   only with a new track.

package dev.ii.tools.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.core.graphics.scale
import dev.ii.tools.link.LinkHub
import dev.ii.tools.live.LiveListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * What plays on the phone, for the PC (PROTOCOL.md, "Media"): each app's media session
 * is sent as `media`, and the PC's helper makes it an MPRIS player, so the shell's media
 * widgets and keys drive it (`mediaCmd`). Read through the notification listener, which
 * is what Android requires for other apps' sessions.
 */
object PhoneMedia {
    private const val PREFS = "media"
    private const val ENABLED = "enabled"
    private const val ART_MAX = 300

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var manager: MediaSessionManager? = null
    private val sessions = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    /** The track each app last sent, so the cover goes once per track. */
    private val lastTrack = mutableMapOf<String, String>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        val l = ctx ?: return
        if (on) start(l) else stop()
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> = if (ctx != null && isEnabled(c)) listOf("media") else emptyList()

    /** From the listener, once it is bound (sessions are only readable then). */
    fun start(c: Context) {
        ctx = c
        if (manager != null || !isEnabled(c)) return
        runCatching {
            val m = c.getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(c, LiveListener::class.java)
            m.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            manager = m
            track(m.getActiveSessions(component))
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        manager = null
        sessions.keys.toList().forEach(::forget)
    }

    private fun track(controllers: List<MediaController>) {
        val c = ctx ?: return
        val now = controllers.filter { it.packageName != c.packageName }.associateBy { it.packageName }
        (sessions.keys - now.keys).forEach(::forget)
        for ((pkg, controller) in now) {
            if (sessions[pkg] === controller) continue
            sessions[pkg]?.let { old -> callbacks[pkg]?.let(old::unregisterCallback) }
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = send(pkg)
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    send(pkg)
                    dev.ii.tools.audio.VolumeKeys.onLocalPlaybackChanged()
                }
                override fun onSessionDestroyed() = forget(pkg)
            }
            controller.registerCallback(cb, main)
            sessions[pkg] = controller
            callbacks[pkg] = cb
            lastTrack.remove(pkg)
            send(pkg)
        }
        dev.ii.tools.audio.VolumeKeys.onLocalPlaybackChanged()
    }

    fun isPlayingLocally(): Boolean = sessions.values.any {
        val state = it.playbackState?.state
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun forget(pkg: String) {
        sessions.remove(pkg)?.let { c -> callbacks.remove(pkg)?.let(c::unregisterCallback) }
        lastTrack.remove(pkg)
        if (LinkHub.isConnected()) LinkHub.sendJson(JSONObject().put("t", "mediaGone").put("source", pkg))
        dev.ii.tools.audio.VolumeKeys.onLocalPlaybackChanged()
    }

    /** Every session again: the link just came up (and the PC takes media). */
    fun sendAll() {
        lastTrack.clear()
        sessions.keys.toList().forEach(::send)
    }

    private fun send(pkg: String) {
        if (!LinkHub.isConnected() || !LinkHub.anyHas("media")) return
        val controller = sessions[pkg] ?: return
        val c = ctx ?: return
        val meta = controller.metadata
        val state = controller.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR).orEmpty()
        // Nothing to show yet (a session warming up): wait for its metadata.
        if (title.isEmpty() && state == null) return
        val actions = state?.actions ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val m = JSONObject()
            .put("t", "media")
            .put("source", pkg)
            .put("app", label(c, pkg))
            .put("title", title)
            .put("artist", artist)
            .put("album", meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            .put("playing", playing)
            .put("position", position(state))
            .put("length", meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            .put("rate", (state?.playbackSpeed ?: 1f).toDouble())
            .put("volume", volume(controller))
            .put("canPlay", playing || actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L)
            .put("canPause", !playing || actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L)
            .put("canNext", actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
            .put("canPrevious", actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            .put("canSeek", actions and PlaybackState.ACTION_SEEK_TO != 0L)
        webUrl(meta)?.let { m.put("url", it) }
        val trackKey = "$title\u0001$artist"
        if (lastTrack[pkg] != trackKey) {
            lastTrack[pkg] = trackKey
            art(meta)?.let { m.put("art", it) }
        }
        LinkHub.sendJson(m)
    }

    /**
     * The track's web page for "Continue on PC", when the app gives one: an http(s) media
     * URI, or a Spotify URI/ID turned into its open.spotify.com page. Most apps give none.
     */
    private fun webUrl(meta: MediaMetadata?): String? {
        meta ?: return null
        val candidates = listOf(
            MediaMetadata.METADATA_KEY_MEDIA_URI,
            MediaMetadata.METADATA_KEY_MEDIA_ID,
        ).mapNotNull { meta.getString(it)?.trim()?.takeIf { v -> v.isNotEmpty() } }
        for (v in candidates) {
            if (v.startsWith("https://") || v.startsWith("http://")) return v
            val spotify = Regex("^spotify:(track|episode):([A-Za-z0-9]{22})$").matchEntire(v)
            if (spotify != null) return "https://open.spotify.com/${spotify.groupValues[1]}/${spotify.groupValues[2]}"
        }
        return null
    }

    /** Where playback is now, not when the state was last written. */
    private fun position(state: PlaybackState?): Long {
        state ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0) return state.position
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (elapsed * state.playbackSpeed).toLong()
    }

    private fun volume(c: MediaController): Int {
        val info = c.playbackInfo
        return if (info.maxVolume > 0) 100 * info.currentVolume / info.maxVolume else 100
    }

    private fun art(meta: MediaMetadata?): String? = runCatching {
        val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: return null
        val scale = minOf(1f, ART_MAX.toFloat() / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f) bmp.scale((bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1)) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 82, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private val labels = mutableMapOf<String, String>()
    private fun label(c: Context, pkg: String) = labels.getOrPut(pkg) {
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    // ── From the PC ───────────────────────────────────────────────────────

    /** A command for whatever is playing (or the last session): the PC's routines have no source to name. */
    fun control(cmd: String) {
        main.post {
            val controller = sessions.values.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                ?: sessions.values.firstOrNull() ?: return@post
            val t = controller.transportControls
            runCatching {
                when (cmd) {
                    "play" -> t.play()
                    "pause" -> t.pause()
                    "next" -> t.skipToNext()
                    "previous" -> t.skipToPrevious()
                }
            }
        }
    }

    fun command(m: JSONObject) {
        val c = ctx ?: return
        if (!isEnabled(c)) return
        main.post {
            val controller = sessions[m.optString("source")] ?: return@post
            val t = controller.transportControls
            runCatching {
                when (m.optString("a")) {
                    "play" -> t.play()
                    "pause" -> t.pause()
                    "next" -> t.skipToNext()
                    "previous" -> t.skipToPrevious()
                    "seek" -> t.seekTo(m.optLong("value"))
                    "volume" -> {
                        val info = controller.playbackInfo
                        controller.setVolumeTo((info.maxVolume * m.optInt("value") / 100.0 + 0.5).toInt().coerceIn(0, info.maxVolume), 0)
                    }
                }
            }
        }
    }
}
