// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Art Pinch <leonardo90690@gmail.com> (org.kde.kdeconnect.plugins.systemvolume.SystemVolumeProvider)
// SPDX-FileContributor: ii tools: framework MediaSession and VolumeProvider over the ii link
//   `audio` topic, standalone silent session when no PC media plays, local playback detection
//   via AudioManager so hardware volume keys return to the phone immediately.

package dev.ii.tools.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.ii.tools.link.AudioNode
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.media.PcMedia
import dev.ii.tools.media.PhoneMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Routes hardware volume keys (and Android's volume panel) to the PC's default output
 * while enabled, the link is online, and the phone is not playing media locally.
 *
 * When PC media is actively playing via [PcMedia], the [VolumeProvider] attaches directly
 * to that session so the two sessions do not fight for top of `MediaSessionStack`.
 * Otherwise a silent `MediaSession` (no notification, no media-button callback) holds
 * `STATE_PLAYING` only while local playback is idle.
 */
object VolumeKeys {
    private const val PREFS = "audio_volume_keys"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_VOLUME = 100
    private const val VOLUME_STEP = 5
    private const val SETTLE_MS = 600L
    private const val SEND_THROTTLE_MS = 50L
    private const val REACTIVATE_DELAY_MS = 400L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var app: Context
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var linkJob: Job? = null
    private var audioJob: Job? = null
    private var linkAudioReady = false

    private var audioManager: AudioManager? = null
    private var hasLocalAudio = false
    private var audioModeNormal = true
    private var pendingReactivate = false

    private var currentOutput: AudioNode? = null
    private var standaloneSession: MediaSession? = null
    private var currentProvider: VolumeProvider? = null

    private var lastSentStep: Int? = null
    private var lastSentAt = 0L
    private var pendingMute: Boolean? = null
    private var pendingMuteAt = 0L

    private var lastSendMs = 0L
    private var pendingVolumeSend: Int? = null

    private val reactivateRunnable = Runnable {
        pendingReactivate = false
        syncSession()
    }

    private val flushSend = Runnable {
        val step = pendingVolumeSend ?: return@Runnable
        pendingVolumeSend = null
        sendVolumeNow(step)
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        // The configs list idle and paused players too, and their state is not public: the
        // callback only says something changed, isMusicActive says whether sound is playing.
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val playing = audioManager?.isMusicActive == true
            if (playing == hasLocalAudio) return
            hasLocalAudio = playing
            onBusyStateChanged()
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val normal = mode == AudioManager.MODE_NORMAL
        if (normal == audioModeNormal) return@OnModeChangedListener
        audioModeNormal = normal
        onBusyStateChanged()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        _enabled.value = prefs(app).getBoolean(KEY_ENABLED, false)
        syncLinkWatcher()
    }

    fun isEnabled(ctx: Context): Boolean =
        if (::app.isInitialized) _enabled.value
        else prefs(ctx.applicationContext).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        if (!::app.isInitialized) init(ctx)
        prefs(app).edit().putBoolean(KEY_ENABLED, on).apply()
        if (_enabled.value == on) return
        _enabled.value = on
        main.post { syncLinkWatcher() }
    }

    /** Called by [PcMedia] when its session starts, pauses, resumes, or clears. */
    fun onPcMediaChanged() {
        if (!::app.isInitialized || !_enabled.value || !linkAudioReady) return
        if (Looper.myLooper() == Looper.getMainLooper()) syncSession()
        else main.post { syncSession() }
    }

    /** Called by [PhoneMedia] when another app's local media session changes playback state. */
    fun onLocalPlaybackChanged() {
        if (!::app.isInitialized || !_enabled.value || !linkAudioReady) return
        if (Looper.myLooper() == Looper.getMainLooper()) onBusyStateChanged()
        else main.post { onBusyStateChanged() }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun syncLinkWatcher() {
        if (!_enabled.value) {
            linkJob?.cancel()
            linkJob = null
            stopActive()
            return
        }
        if (linkJob?.isActive == true) return
        linkJob = scope.launch {
            combine(LinkHub.status, LinkHub.shell) { st, sh ->
                st is LinkStatus.Online && sh?.caps?.contains("audio") == true
            }.distinctUntilChanged().collect { ready ->
                if (ready) startActive() else stopActive()
            }
        }
    }

    private fun startActive() {
        if (linkAudioReady) return
        linkAudioReady = true

        val am = app.getSystemService(AudioManager::class.java)
        audioManager = am
        if (am != null) {
            hasLocalAudio = am.isMusicActive
            audioModeNormal = am.mode == AudioManager.MODE_NORMAL
            am.registerAudioPlaybackCallback(playbackCallback, main)
            am.addOnModeChangedListener(app.mainExecutor, modeListener)
        }

        audioJob = scope.launch {
            LinkHub.audio.collect { onAudioUpdate(it?.output) }
        }
    }

    private fun stopActive() {
        linkAudioReady = false
        audioJob?.cancel()
        audioJob = null
        main.removeCallbacks(reactivateRunnable)
        main.removeCallbacks(flushSend)
        pendingReactivate = false
        pendingVolumeSend = null
        lastSentStep = null
        pendingMute = null

        audioManager?.let { am ->
            runCatching { am.unregisterAudioPlaybackCallback(playbackCallback) }
            runCatching { am.removeOnModeChangedListener(modeListener) }
        }
        audioManager = null
        hasLocalAudio = false
        audioModeNormal = true
        currentOutput = null

        releaseStandaloneSession()
        currentProvider = null
        PcMedia.detachVolumeProvider()
    }

    private fun isLocalBusy(): Boolean =
        !audioModeNormal || hasLocalAudio || PhoneMedia.isPlayingLocally()

    private fun onBusyStateChanged() {
        if (!linkAudioReady) return
        if (isLocalBusy()) {
            // Release routing immediately so the next volume press hits the phone.
            main.removeCallbacks(reactivateRunnable)
            pendingReactivate = false
            syncSession()
        } else if (standaloneSession == null && !PcMedia.hasRemoteProvider()) {
            // Brief cooldown avoids bouncing between consecutive tracks or clips.
            pendingReactivate = true
            main.removeCallbacks(reactivateRunnable)
            main.postDelayed(reactivateRunnable, REACTIVATE_DELAY_MS)
        }
    }

    private fun onAudioUpdate(out: AudioNode?) {
        val prev = currentOutput
        currentOutput = out
        if (out != null) {
            if (prev != null && prev.id != out.id) {
                lastSentStep = null
                pendingMute = null
                pendingVolumeSend = null
                main.removeCallbacks(flushSend)
            }
            val display = resolveDisplayVolume(out)
            currentProvider?.let { if (it.currentVolume != display) it.currentVolume = display }
        }
        syncSession()
    }

    private fun syncSession() {
        val out = currentOutput
        val wantRemote = _enabled.value && linkAudioReady && out != null && !isLocalBusy() && !pendingReactivate
        if (!wantRemote || out == null) {
            releaseStandaloneSession()
            currentProvider = null
            PcMedia.detachVolumeProvider()
            return
        }

        val display = resolveDisplayVolume(out)
        if (PcMedia.isSessionPlaying()) {
            releaseStandaloneSession()
            if (!PcMedia.hasRemoteProvider()) {
                val provider = makeProvider(display)
                currentProvider = provider
                PcMedia.attachVolumeProvider(provider)
            } else {
                currentProvider?.let { if (it.currentVolume != display) it.currentVolume = display }
            }
            return
        }

        PcMedia.detachVolumeProvider()
        val session = standaloneSession
        if (session == null) {
            val provider = makeProvider(display)
            currentProvider = provider
            standaloneSession = createStandaloneSession(provider)
        } else {
            currentProvider?.let { if (it.currentVolume != display) it.currentVolume = display }
        }
    }

    private fun createStandaloneSession(provider: VolumeProvider): MediaSession =
        MediaSession(app, "ii tools volume").apply {
            setFlags(0)
            // Framework MediaSession dispatches VolumeProvider callbacks through its Callback handler.
            setCallback(
                object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent) = false
                },
                main,
            )
            // STATE_PLAYING is required for MediaSessionStack.getDefaultVolumeSession() to pick this session;
            // no MediaStyle notification is posted, so SystemUI will not draw a shade player.
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 0f)
                    .build(),
            )
            setPlaybackToRemote(provider)
            isActive = true
        }

    private fun releaseStandaloneSession() {
        standaloneSession?.let { s ->
            runCatching {
                s.isActive = false
                s.release()
            }
        }
        standaloneSession = null
    }

    private fun makeProvider(initialVolume: Int): VolumeProvider =
        object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, MAX_VOLUME, initialVolume.coerceIn(0, MAX_VOLUME)) {
            override fun onAdjustVolume(direction: Int) {
                adjust(direction)
            }

            override fun onSetVolumeTo(volume: Int) {
                setTo(volume)
            }
        }

    private fun adjust(direction: Int) {
        val out = currentOutput ?: return
        val muted = effectiveMute(out)
        val base = settledStep(out)
        when (direction) {
            AudioManager.ADJUST_RAISE -> {
                val target = (base + VOLUME_STEP).coerceIn(if (muted) VOLUME_STEP else 0, MAX_VOLUME)
                applyStep(out, target, unmute = muted)
            }
            AudioManager.ADJUST_LOWER -> {
                if (muted) return
                applyStep(out, (base - VOLUME_STEP).coerceIn(0, MAX_VOLUME), unmute = false)
            }
            AudioManager.ADJUST_TOGGLE_MUTE -> applyMute(out, !muted)
            AudioManager.ADJUST_MUTE -> applyMute(out, true)
            AudioManager.ADJUST_UNMUTE -> applyMute(out, false)
        }
    }

    private fun setTo(volume: Int) {
        val out = currentOutput ?: return
        val clamped = volume.coerceIn(0, MAX_VOLUME)
        applyStep(out, clamped, unmute = clamped > 0 && effectiveMute(out))
    }

    private fun applyStep(out: AudioNode, step: Int, unmute: Boolean) {
        val now = SystemClock.elapsedRealtime()
        lastSentStep = step
        lastSentAt = now
        if (unmute) {
            pendingMute = false
            pendingMuteAt = now
            LinkHub.send("mute", JSONObject().put("id", out.id).put("mute", false))
        }
        currentProvider?.let { if (it.currentVolume != step) it.currentVolume = step }

        val elapsed = now - lastSendMs
        if (elapsed >= SEND_THROTTLE_MS) {
            main.removeCallbacks(flushSend)
            pendingVolumeSend = null
            sendVolumeNow(step)
        } else {
            pendingVolumeSend = step
            main.removeCallbacks(flushSend)
            main.postDelayed(flushSend, SEND_THROTTLE_MS - elapsed)
        }
    }

    private fun applyMute(out: AudioNode, mute: Boolean) {
        pendingMute = mute
        pendingMuteAt = SystemClock.elapsedRealtime()
        LinkHub.send("mute", JSONObject().put("id", out.id).put("mute", mute))
        val display = if (mute) 0 else settledStep(out)
        currentProvider?.let { if (it.currentVolume != display) it.currentVolume = display }
    }

    private fun sendVolumeNow(step: Int) {
        val out = currentOutput ?: return
        lastSendMs = SystemClock.elapsedRealtime()
        LinkHub.send("volume", JSONObject().put("id", out.id).put("vol", step.toDouble() / MAX_VOLUME))
    }

    private fun resolveDisplayVolume(out: AudioNode): Int {
        val now = SystemClock.elapsedRealtime()
        val remoteStep = volumeStep(out)
        val pendingStep = lastSentStep
        val step = if (pendingStep != null && now - lastSentAt < SETTLE_MS && remoteStep != pendingStep) {
            pendingStep
        } else {
            lastSentStep = null
            remoteStep
        }
        if (pendingMute != null && (now - pendingMuteAt >= SETTLE_MS || out.mute == pendingMute)) {
            pendingMute = null
        }
        return if (effectiveMute(out)) 0 else step
    }

    private fun settledStep(out: AudioNode): Int {
        val pending = lastSentStep
        return if (pending != null && SystemClock.elapsedRealtime() - lastSentAt < SETTLE_MS) pending else volumeStep(out)
    }

    private fun effectiveMute(out: AudioNode): Boolean {
        val pending = pendingMute
        return if (pending != null && SystemClock.elapsedRealtime() - pendingMuteAt < SETTLE_MS) pending else out.mute
    }

    private fun volumeStep(out: AudioNode): Int =
        (out.vol * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME)
}
