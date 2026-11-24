package dev.ii.tools.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import dev.ii.tools.R
import dev.ii.tools.live.LiveListener
import kotlinx.coroutines.launch

/**
 * What plays on the phone, for the media widgets: any app's media session, read through
 * the notification listener (Android's gate for other apps' sessions). While a media
 * widget is placed it follows the sessions and redraws on a new track or state. The
 * position label is a Chronometer the launcher counts; the seek line and ring step only
 * while something plays and the screen is on, about a hundred steps per track (every 1 to
 * 5 s), as a partial update of a few small layers.
 */
object WidgetMedia {
    val WIDGETS: Array<Class<out MediaWidget>> = arrayOf(
        PosterMediaWidget::class.java,
        DeckMediaWidget::class.java,
        HaloMediaWidget::class.java,
        CoverMediaWidget::class.java,
    )

    /** The widgets with a seek line or ring: only these tick. */
    private val TICKING: Array<Class<out MediaWidget>> = arrayOf(
        PosterMediaWidget::class.java,
        DeckMediaWidget::class.java,
        HaloMediaWidget::class.java,
    )

    /** One reading of the active player. */
    class Snapshot(
        val allowed: Boolean,
        val hasPlayer: Boolean,
        val playing: Boolean,
        val title: String,
        val artist: String,
        val art: Bitmap?,
        val playerName: String,
        val positionMs: Long,
        val durationMs: Long,
        val canSeek: Boolean,
        val canPrevious: Boolean,
        val canNext: Boolean,
        val open: PendingIntent?,
    ) {
        val hasLength get() = durationMs > 0
        val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        /** The art (the cover) changes the layout: a track key for it. */
        val positionText get() = time(positionMs)
        val lengthText get() = if (hasLength) time(durationMs) else "––:––"
    }

    private const val TICK_MIN_MS = 1_000L
    private const val TICK_MAX_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var manager: MediaSessionManager? = null
    private val watched = mutableListOf<MediaController>()
    private var screenOn = true
    /** Something changed while the screen was off. */
    private var dirty = false
    private var screenReceiver: BroadcastReceiver? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
        override fun onSessionDestroyed() = changed()
    }

    private val redraw = Runnable { app?.let { ShellWidget.refresh(it, *WIDGETS) }; schedule() }
    private val tick = Runnable { app?.let(::tickNow); schedule() }

    fun component(ctx: Context) = ComponentName(ctx, LiveListener::class.java)

    /** The active controller: a playing one first, else the most recent. Null without access. */
    fun controller(ctx: Context): MediaController? = runCatching {
        val list = ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(ctx))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }.getOrNull()

    fun allowed(ctx: Context): Boolean =
        android.app.NotificationManager::class.java.let { ctx.getSystemService(it).isNotificationListenerAccessGranted(component(ctx)) }

    fun snapshot(ctx: Context): Snapshot {
        val allowed = allowed(ctx)
        val c = if (allowed) controller(ctx) else null
        val md = c?.metadata
        val st = c?.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (duration > 0) pos = pos.coerceIn(0, duration)
        val actions = st?.actions ?: 0L
        val name = c?.packageName?.let { pkg ->
            runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.orEmpty()
        val open = c?.sessionActivity ?: c?.packageName?.let { pkg ->
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ShellWidget.activity(ctx, it, 40) }
        }
        return Snapshot(
            allowed = allowed,
            hasPlayer = c != null,
            playing = playing,
            title = when {
                !allowed -> ctx.getString(R.string.wm_allow)
                c == null -> ctx.getString(R.string.wm_nothing)
                else -> md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(R.string.wm_unknown_title)
            },
            artist = when {
                !allowed -> ctx.getString(R.string.wm_allow_body)
                c == null -> ctx.getString(R.string.wm_play_something)
                else -> md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(R.string.wm_unknown_artist)
            },
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playerName = name,
            positionMs = pos,
            durationMs = duration,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            open = if (!allowed) ShellWidget.activity(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), 41) else open,
        )
    }

    /** From the listener once bound, and when a media widget is placed. Idempotent. */
    fun start(ctx: Context) {
        val c = ctx.applicationContext
        main.post {
            if (!ShellWidget.placed(c, *WIDGETS)) return@post stop()
            app = c
            if (screenReceiver == null) screenOn = c.getSystemService(PowerManager::class.java).isInteractive
            if (manager == null) runCatching {
                val m = c.getSystemService(MediaSessionManager::class.java)
                m.addOnActiveSessionsChangedListener(sessionsChanged, component(c), main)
                manager = m
                follow(m.getActiveSessions(component(c)))
            }
            if (screenReceiver == null) {
                screenOn = c.getSystemService(PowerManager::class.java).isInteractive
                screenReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        screenOn = intent.action == Intent.ACTION_SCREEN_ON
                        when {
                            !screenOn -> main.removeCallbacks(tick)
                            dirty -> {
                                dirty = false
                                changed()
                            }
                            // The seek line moved on while it was dark; the clock counted by itself.
                            else -> main.post(tick)
                        }
                    }
                }.also {
                    c.registerReceiver(it, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
                }
            }
        }
    }

    /** No media widget left, or the listener went away. */
    fun stop() {
        main.removeCallbacks(tick)
        main.removeCallbacks(redraw)
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        manager = null
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched.clear()
        screenReceiver?.let { r -> runCatching { app?.unregisterReceiver(r) } }
        screenReceiver = null
    }

    /** Every session: another one may start playing while the shown one sits paused. */
    private fun follow(list: List<MediaController>) {
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched.clear()
        list.forEach { it.registerCallback(callback, main); watched += it }
        changed()
    }

    /** A burst of callbacks (a new track sends several) makes one redraw. */
    private fun changed() {
        // Nobody sees a widget with the screen off: draw once when it comes on.
        if (!screenOn) {
            dirty = true
            return
        }
        main.removeCallbacks(redraw)
        main.postDelayed(redraw, 150)
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        val c = app ?: return
        val st = watched.firstNotNullOfOrNull { c -> c.playbackState?.takeIf { it.state == PlaybackState.STATE_PLAYING } } ?: return
        if (!screenOn || !ShellWidget.placed(c, *TICKING)) return
        // A step of about 1 % of the track: what a seek line or ring can show.
        val length = watched.firstNotNullOfOrNull { mc -> mc.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } } ?: 0L
        val step = (length / 100).coerceIn(TICK_MIN_MS, TICK_MAX_MS)
        main.postDelayed(tick, step)
    }

    private fun tickNow(ctx: Context) {
        val m = snapshot(ctx)
        val manager = AppWidgetManager.getInstance(ctx)
        TICKING.forEach { cls ->
            val ids = manager.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) return@forEach
            val widget = cls.getDeclaredConstructor().newInstance()
            ShellWidget.scope.launch {
                ids.forEach { id ->
                    runCatching {
                        val (w, h) = widget.box(ctx, manager.getAppWidgetOptions(id))
                        val f = Frame(ctx, widget.designW, widget.designH, w, h, partial = true, stretch = widget.stretch)
                        widget.drawProgress(ctx, f, m)
                        try {
                            manager.partiallyUpdateAppWidget(id, f.views)
                        } finally {
                            f.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        // Chronometer's own format (DateUtils.formatElapsedTime), so the length matches the position beside it.
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
}

/** The media widgets' transport keys. */
class MediaWidgetAction : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val c = WidgetMedia.controller(ctx) ?: return
        val t = c.transportControls
        when (intent.action) {
            PLAY -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            PREVIOUS -> t.skipToPrevious()
            NEXT -> t.skipToNext()
        }
        // Without the listener running the callbacks cannot redraw: do it here.
        WidgetMedia.start(ctx)
    }

    companion object {
        const val PLAY = "dev.ii.tools.widgets.PLAY"
        const val PREVIOUS = "dev.ii.tools.widgets.PREVIOUS"
        const val NEXT = "dev.ii.tools.widgets.NEXT"

        fun intent(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, action.hashCode(), Intent(ctx, MediaWidgetAction::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
