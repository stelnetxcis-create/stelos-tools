@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.AudioNode
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.PcAudio
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.voice.PrivacyUse
import dev.ii.tools.voice.VoiceAvatars
import dev.ii.tools.voice.VoiceEvent
import dev.ii.tools.voice.VoicePrefs
import dev.ii.tools.voice.VoiceSelf
import dev.ii.tools.voice.VoiceState
import dev.ii.tools.voice.VoiceUser
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Voice and meeting console: Discord call controls & speaking roster when in a call,
 * PC default microphone controls outside a call, Discord app volume + output chooser,
 * and active camera/mic/screen sensors (`Privacy`).
 */
@Composable
fun VoiceScreen(onBack: () -> Unit, onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val voice by LinkHub.voice.collectAsStateWithLifecycle()
    val voiceEvent by LinkHub.voiceEvent.collectAsStateWithLifecycle()
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val voiceEnabled by VoicePrefs.enabled(ctx).collectAsStateWithLifecycle()

    LifecycleResumeEffect(voiceEnabled) {
        if (voiceEnabled) LinkHub.subscribeVoice(true)
        onPauseOrDispose { LinkHub.subscribeVoice(false) }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var transientEvent by remember { mutableStateOf<VoiceEvent?>(null) }
    LaunchedEffect(voiceEvent?.serial) {
        val ev = voiceEvent
        if (ev != null) {
            transientEvent = ev
            delay(3_500)
            if (transientEvent?.serial == ev.serial) transientEvent = null
        }
    }

    var selectedUserId by remember { mutableStateOf<String?>(null) }
    var deviceSheetTab by remember { mutableIntStateOf(-1) } // 0 = output, 1 = input

    val v = voice
    val inCall = v?.channel != null
    val online = status is LinkStatus.Online && shell != null
    val supported = shell?.caps?.contains("voice") == true
    val clockEnabled = online && supported && voiceEnabled && inCall && (v?.since ?: 0L) > 0L
    val nowState = rememberClockState(enabled = clockEnabled, read = System::currentTimeMillis)
    val readNow = remember(nowState) { { nowState.value } }

    LaunchedEffect(v?.users) {
        val uid = selectedUserId
        if (uid != null && v?.users?.none { it.id == uid } == true) selectedUserId = null
    }

    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (sideways && online && supported && voiceEnabled) {
        VoiceSideways(
            v = v,
            audio = audio,
            nowMs = readNow,
            transientEvent = transientEvent,
            onSelectUser = { selectedUserId = it },
            onPickDevice = { deviceSheetTab = it },
            onBack = onBack,
        )
    } else {
        ToolScaffold(
            title = stringResource(R.string.voice_title),
            onBack = onBack,
            subtitleContent = {
                when {
                    !voiceEnabled -> Text(
                        stringResource(R.string.voice_setting),
                        style = Expressive.live(15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    v?.channel != null -> CallSubtitleText(
                        channelName = v.channel.name,
                        since = v.since,
                        nowMs = readNow,
                    )
                    else -> {
                        val micText = audio?.mic?.let {
                            if (it.mute) stringResource(R.string.voice_mic_muted) else it.name
                        }
                        if (!micText.isNullOrEmpty()) {
                            Text(
                                micText,
                                style = Expressive.live(15.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
        ) {
            if (!linkGate(pc, status, shell != null)) return@ToolScaffold
            if (!supported) {
                item { Hint(stringResource(R.string.voice_off)) }
                return@ToolScaffold
            }
            if (!voiceEnabled) {
                item { Hint(stringResource(R.string.voice_disabled_hint)) }
                item { VoiceSettingsGroup(voiceEnabled) { VoicePrefs.setEnabled(ctx, it) } }
                return@ToolScaffold
            }

            if (inCall && v != null) {
                item(key = "call_hero") { CallHeroCard(v, readNow) }
                val ev = transientEvent
                if (ev != null) {
                    item(key = "join_leave") { JoinLeaveLine(ev) }
                }
                item(key = "roster_hdr") {
                    GroupHeader(stringResource(R.string.voice_participants))
                }
                item(key = "roster") {
                    ParticipantGrid(
                        users = v.users,
                        selfId = v.self.id,
                        canUserVoice = v.self.canUserVoice,
                        onSelect = { selectedUserId = it.id },
                    )
                }
            } else {
                item(key = "mic_hero") {
                    IdleMicHero(
                        mic = audio?.mic,
                        hasInputs = audio?.inputs?.isNotEmpty() == true,
                        onPickInput = { deviceSheetTab = 1 },
                    )
                }
                if (v != null && (v.status == "auth_required" || v.status == "unavailable" || v.status == "disconnected")) {
                    item(key = "discord_status") { DiscordStatusCard(v.status) }
                }
            }

            item(key = "discord_app_hdr") { GroupHeader(stringResource(R.string.voice_discord_app)) }
            item(key = "discord_app") {
                DiscordAudioCard(
                    audio = audio,
                    onPickOutput = { deviceSheetTab = 0 },
                )
            }

            item(key = "privacy_hdr") { GroupHeader(stringResource(R.string.voice_privacy_title)) }
            item(key = "privacy") { PrivacySection(v?.privacy.orEmpty()) }

            item(key = "settings_hdr") { GroupHeader(stringResource(R.string.settings_title)) }
            item(key = "settings_grp") {
                VoiceSettingsGroup(voiceEnabled) { VoicePrefs.setEnabled(ctx, it) }
            }
        }
    }

    val pickedUser = selectedUserId?.let { id -> v?.users?.firstOrNull { it.id == id } }
    if (pickedUser != null) {
        ParticipantVolumeSheet(
            user = pickedUser,
            onDismiss = { selectedUserId = null },
        )
    }

    val a = audio
    if (deviceSheetTab >= 0 && a != null) {
        DevicePickerSheet(
            audio = a,
            startOnInput = deviceSheetTab == 1,
            onDismiss = { deviceSheetTab = -1 },
            onOpenAudio = {
                deviceSheetTab = -1
                onOpen(Dest.AUDIO)
            },
        )
    }
}

/**
 * Sideways the console fills the screen, bars hidden, nothing scrolls but the roster: the
 * call's controls on the left (or the PC's mic outside a call), who is in it in the middle,
 * and Discord's volume, the output and what uses a sensor on the right.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceSideways(
    v: VoiceState?,
    audio: PcAudio?,
    nowMs: () -> Long,
    transientEvent: VoiceEvent?,
    onSelectUser: (String) -> Unit,
    onPickDevice: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val call = v?.takeIf { it.channel != null }
    val inCall = call != null
    // The page's own surface, so every text under it gets onSurface rather than black.
    Surface(color = Layer.page, contentColor = cs.onSurface, modifier = Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Controls ──
            Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onBack()
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Layer.pane, contentColor = cs.onSurface),
                    ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back)) }
                    Column(Modifier.weight(1f)) {
                        Caption(stringResource(if (inCall) R.string.voice_in_call else R.string.voice_pc_mic), color = cs.onSurfaceVariant)
                        Text(
                            if (call != null) call.channel?.name.orEmpty() else audio?.mic?.name ?: stringResource(R.string.voice_title),
                            style = Expressive.wide(19.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (call != null && call.since > 0L) SideElapsed(call.since, nowMs)
                }
                if (call != null) {
                    val self = call.self
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SideToggle(
                            off = self.mute || self.deaf,
                            label = stringResource(if (self.mute) R.string.voice_muted else R.string.voice_mute),
                            on = R.drawable.rounded_mic_fill1,
                            offIcon = R.drawable.rounded_mic_off,
                            modifier = Modifier.weight(1f),
                        ) { scope.launch { LinkHub.runVoice(JSONObject().put("a", "mute").put("on", !self.mute)) } }
                        SideToggle(
                            off = self.deaf,
                            label = stringResource(if (self.deaf) R.string.voice_deafened else R.string.voice_deafen),
                            on = R.drawable.rounded_headphones,
                            offIcon = R.drawable.rounded_headset_off,
                            modifier = Modifier.weight(1f),
                        ) { scope.launch { LinkHub.runVoice(JSONObject().put("a", "deaf").put("on", !self.deaf)) } }
                        if (self.canVideo) {
                            SideToggle(
                                off = !self.video,
                                label = stringResource(if (self.video) R.string.voice_camera_on else R.string.voice_camera_off),
                                on = R.drawable.rounded_videocam_fill1,
                                offIcon = R.drawable.rounded_videocam_off,
                                modifier = Modifier.weight(1f),
                            ) { scope.launch { LinkHub.runVoice(JSONObject().put("a", "video").put("on", !self.video)) } }
                        }
                    }
                    if (self.canMode) VoiceModeToggle(self, height = 44.dp)
                } else {
                    val mic = audio?.mic
                    if (mic == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        SideToggle(
                            off = mic.mute,
                            label = stringResource(if (mic.mute) R.string.voice_mic_muted else R.string.voice_mic_live),
                            on = R.drawable.rounded_mic_fill1,
                            offIcon = R.drawable.rounded_mic_off,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { setMute(mic, !mic.mute) }
                        val volume = rememberLiveVolume(mic)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigSlider(volume.value, volume.change, volume.finish, stringResource(R.string.audio_mic), height = 44.dp, enabled = !mic.mute, modifier = Modifier.weight(1f))
                            if (audio?.inputs?.isNotEmpty() == true) {
                                FilledIconButton(
                                    onClick = { onPickDevice(1) },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Layer.pane, contentColor = cs.onSurface),
                                    modifier = Modifier.size(44.dp),
                                ) { Icon(painterResource(R.drawable.rounded_keyboard_arrow_down), stringResource(R.string.voice_pick_input)) }
                            }
                        }
                    }
                }
            }

            // ── Roster ──
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (call != null) {
                    Row(Modifier.height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Caption(stringResource(R.string.voice_participants), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.voice_participants_count, call.users.size), style = Expressive.title(13.sp), color = cs.onSurfaceVariant)
                    }
                    if (transientEvent != null) JoinLeaveLine(transientEvent)
                    val speakingIds by LinkHub.voiceSpeak.collectAsStateWithLifecycle()
                    val avatars by VoiceAvatars.avatars.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(Radius.row)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(call.users, key = { it.id }) { user ->
                            val isSelf = user.id == call.self.id
                            ParticipantTile(
                                user = user,
                                isSelf = isSelf,
                                speaking = user.id in speakingIds,
                                avatar = avatars[user.avatar],
                                clickable = !isSelf && call.self.canUserVoice,
                                onClick = { onSelectUser(user.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    ExpressiveCard(Layer.pane, cs.onSurface, modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            StateShape(active = false, size = 72.dp, color = Layer.field) {
                                Icon(painterResource(R.drawable.rounded_headset_mic), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
                            }
                            Text(stringResource(R.string.voice_no_call), style = Expressive.wide(18.sp), textAlign = TextAlign.Center)
                            if (v != null && (v.status == "auth_required" || v.status == "unavailable" || v.status == "disconnected")) {
                                DiscordStatusActions(v.status)
                            }
                        }
                    }
                }
            }

            // ── Sound and sensors ──
            Column(Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SideAudioPane(audio, onPickOutput = { onPickDevice(0) }, modifier = Modifier.weight(1f).fillMaxWidth())
                SidePrivacy(v?.privacy.orEmpty())
            }
        }
    }
}

@Composable
private fun SideElapsed(since: Long, nowMs: () -> Long) {
    val cs = MaterialTheme.colorScheme
    val secs by remember(since, nowMs) { derivedStateOf { ((nowMs() - since) / 1000L).coerceAtLeast(0L) } }
    Box(Modifier.clip(CircleShape).background(cs.primaryContainer).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(clockText(secs), style = Expressive.digits(20.sp), color = cs.onPrimaryContainer)
    }
}

/**
 * A full-height call control. Live it is the pane with a primary scallop; off (muted,
 * deafened) the whole tile turns to the error family and the badge becomes a circle.
 */
@Composable
private fun SideToggle(off: Boolean, label: String, @DrawableRes on: Int, @DrawableRes offIcon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (off) cs.errorContainer else Layer.pane, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "sideToggle")
    val fg = if (off) cs.onErrorContainer else cs.onSurface
    Surface(
        onClick = {
            haptics.performHapticFeedback(if (off) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onClick()
        },
        shape = RoundedCornerShape(28.dp),
        color = bg,
        contentColor = fg,
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            StateShape(active = !off, size = 76.dp, color = if (off) cs.error else cs.primaryContainer, on = MaterialShapes.Cookie9Sided) {
                Icon(painterResource(if (off) offIcon else on), null, tint = if (off) cs.onError else cs.onPrimaryContainer, modifier = Modifier.size(34.dp))
            }
            Text(label, style = Expressive.title(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Voice activity or push to talk, as connected toggle buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceModeToggle(self: VoiceSelf, height: Dp = 46.dp) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val ptt = self.mode == "PUSH_TO_TALK"
    fun pick(mode: String) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { LinkHub.runVoice(JSONObject().put("a", "mode").put("mode", mode)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(
            checked = !ptt,
            onCheckedChange = { if (ptt) pick("VOICE_ACTIVITY") },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.weight(1f).height(height),
        ) {
            Icon(painterResource(R.drawable.rounded_graphic_eq), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.voice_activity), style = Expressive.title(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ToggleButton(
            checked = ptt,
            onCheckedChange = { if (!ptt) pick("PUSH_TO_TALK") },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.weight(1f).height(height),
        ) {
            Icon(painterResource(R.drawable.rounded_touch_app), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (ptt && self.pttShortcut.isNotEmpty()) self.pttShortcut else stringResource(R.string.voice_ptt),
                style = Expressive.title(14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The output to switch and Discord's own volume, on one pane. */
@Composable
private fun SideAudioPane(audio: PcAudio?, onPickOutput: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val discordApps = remember(audio?.apps) { audio?.apps?.filter { isDiscordAudioApp(it.name) }.orEmpty() }
    val out = audio?.output
    ExpressiveCard(Layer.pane, cs.onSurface, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Caption(stringResource(R.string.voice_discord_app), color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            if (discordApps.isEmpty()) {
                Text(
                    stringResource(R.string.voice_discord_app_none),
                    style = Expressive.title(14.sp),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                discordApps.take(2).forEach { DiscordAppSlider(it) }
            }
            Spacer(Modifier.weight(1f))
            if (out != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Layer.onPane)
                        .clickable { onPickOutput() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(painterResource(R.drawable.rounded_speaker), null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Caption(stringResource(R.string.voice_pick_output), color = cs.onSurfaceVariant)
                        Text(out.name, style = Expressive.title(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.rounded_keyboard_arrow_down), null, tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** What uses the camera, mic or screen now; one quiet line when nothing does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SidePrivacy(items: List<PrivacyUse>) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption(stringResource(R.string.voice_privacy_title), color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            if (items.isEmpty()) {
                Text(stringResource(R.string.voice_privacy_none), style = Expressive.title(14.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxLines = 2) {
                    items.forEach { PrivacyChip(it) }
                }
            }
        }
    }
}

@Composable
private fun PrivacyChip(item: PrivacyUse) {
    val cs = MaterialTheme.colorScheme
    val (icon, kindLabel) = when (item.kind) {
        "camera" -> R.drawable.rounded_videocam to stringResource(R.string.voice_sensor_camera)
        "microphone" -> R.drawable.rounded_mic to stringResource(R.string.voice_sensor_mic)
        else -> R.drawable.rounded_screen_share to stringResource(R.string.voice_sensor_screen)
    }
    Row(
        Modifier.clip(CircleShape).background(cs.secondaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), null, tint = cs.onSecondaryContainer, modifier = Modifier.size(16.dp))
        Text(
            if (item.app.isNotEmpty()) "$kindLabel · ${item.app}" else kindLabel,
            style = Expressive.title(13.sp),
            color = cs.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CallSubtitleText(
    channelName: String,
    since: Long,
    nowMs: () -> Long,
    style: TextStyle = Expressive.live(15.sp),
    color: Color = Color.Unspecified,
) {
    val elapsedSecs by remember(since, nowMs) {
        derivedStateOf {
            if (since > 0L) ((nowMs() - since) / 1000L).coerceAtLeast(0L) else -1L
        }
    }
    val text = listOfNotNull(
        channelName.ifEmpty { null },
        elapsedSecs.takeIf { it >= 0L }?.let(::clockText),
    ).joinToString(" · ")
    if (text.isNotEmpty()) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CallElapsedPill(since: Long, nowMs: () -> Long) {
    val cs = MaterialTheme.colorScheme
    val elapsedSecs by remember(since, nowMs) {
        derivedStateOf { ((nowMs() - since) / 1000L).coerceAtLeast(0L) }
    }
    Box(
        Modifier
            .clip(CircleShape)
            .background(cs.onPrimaryContainer.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(clockText(elapsedSecs), style = Expressive.digitsSoft(18.sp), color = cs.onPrimaryContainer)
    }
}

@Composable
private fun CallHeroCard(v: VoiceState, nowMs: () -> Long) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val self = v.self
    val backendLabel = when (v.backend) {
        "vencord" -> "Vesktop"
        "discord" -> "Discord"
        else -> ""
    }
    val sub = listOfNotNull(
        backendLabel.ifEmpty { null },
        stringResource(R.string.voice_participants_count, v.users.size),
    ).joinToString(" · ")

    ExpressiveCard(cs.primaryContainer, cs.onPrimaryContainer) {
        Ornament(MaterialShapes.Cookie7Sided, 320.dp, cs.onPrimaryContainer, Alignment.TopEnd, x = 130.dp, y = (-120).dp, alpha = 0.08f)
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeBox(MaterialShapes.Cookie9Sided, 48.dp, cs.primary) {
                    Icon(painterResource(R.drawable.rounded_headset_mic), null, tint = cs.onPrimary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.voice_in_call))
                    Text(v.channel?.name.orEmpty(), style = Expressive.wide(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = Expressive.live(13.sp), color = cs.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 1)
                }
                if (v.since > 0L) {
                    CallElapsedPill(since = v.since, nowMs = nowMs)
                }
            }

            // Huge Mute & Deafen buttons: circle when live, scalloped + error family when muted/deafened.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigCallToggle(
                    active = self.mute,
                    label = stringResource(if (self.mute) R.string.voice_muted else R.string.voice_mute),
                    icon = if (self.mute) R.drawable.rounded_mic_off else R.drawable.rounded_mic_fill1,
                    activeShape = MaterialShapes.SoftBurst,
                    onClick = {
                        haptics.performHapticFeedback(if (self.mute) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        scope.launch { LinkHub.runVoice(JSONObject().put("a", "mute").put("on", !self.mute)) }
                    },
                    modifier = Modifier.weight(1f),
                )
                BigCallToggle(
                    active = self.deaf,
                    label = stringResource(if (self.deaf) R.string.voice_deafened else R.string.voice_deafen),
                    icon = if (self.deaf) R.drawable.rounded_headset_off else R.drawable.rounded_headphones,
                    activeShape = MaterialShapes.Boom,
                    onClick = {
                        haptics.performHapticFeedback(if (self.deaf) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        scope.launch { LinkHub.runVoice(JSONObject().put("a", "deaf").put("on", !self.deaf)) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (self.canMode) {
                val ptt = self.mode == "PUSH_TO_TALK"
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        ToggleButton(
                            checked = !ptt,
                            onCheckedChange = {
                                if (ptt) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    scope.launch { LinkHub.runVoice(JSONObject().put("a", "mode").put("mode", "VOICE_ACTIVITY")) }
                                }
                            },
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Icon(painterResource(R.drawable.rounded_graphic_eq), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.voice_activity), style = Expressive.title(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ToggleButton(
                            checked = ptt,
                            onCheckedChange = {
                                if (!ptt) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    scope.launch { LinkHub.runVoice(JSONObject().put("a", "mode").put("mode", "PUSH_TO_TALK")) }
                                }
                            },
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Icon(painterResource(R.drawable.rounded_touch_app), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.voice_ptt), style = Expressive.title(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (ptt && self.pttShortcut.isNotEmpty()) {
                        Text(
                            stringResource(R.string.voice_ptt_hint, self.pttShortcut),
                            style = Expressive.live(12.sp),
                            color = cs.onPrimaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }

            // Camera row appears only if the shell backend actually supports toggling video.
            if (self.canVideo) {
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(if (self.video) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        scope.launch { LinkHub.runVoice(JSONObject().put("a", "video").put("on", !self.video)) }
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = if (self.video) cs.primary else cs.onPrimaryContainer.copy(alpha = 0.12f),
                    contentColor = if (self.video) cs.onPrimary else cs.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painterResource(if (self.video) R.drawable.rounded_videocam_fill1 else R.drawable.rounded_videocam_off),
                            null,
                            Modifier.size(22.dp),
                        )
                        Text(
                            stringResource(if (self.video) R.string.voice_camera_on else R.string.voice_camera_off),
                            style = Expressive.title(15.sp),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BigCallToggle(
    active: Boolean,
    label: String,
    icon: Int,
    activeShape: RoundedPolygon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        targetValue = if (active) cs.errorContainer else cs.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "callToggleBg",
    )
    val fg = if (active) cs.onErrorContainer else cs.onSurface
    val badgeBg = if (active) cs.error else cs.primaryContainer
    val badgeFg = if (active) cs.onError else cs.onPrimaryContainer
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(if (active) 28.dp else 22.dp),
        color = bg,
        contentColor = fg,
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StateShape(active = active, size = 56.dp, color = badgeBg, on = activeShape) {
                Icon(painterResource(icon), null, tint = badgeFg, modifier = Modifier.size(26.dp))
            }
            Text(label, style = Expressive.title(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun JoinLeaveLine(ev: VoiceEvent) {
    val cs = MaterialTheme.colorScheme
    val joined = ev.kind == "join"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (joined) cs.secondaryContainer else Layer.pane)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painterResource(if (joined) R.drawable.rounded_person_add else R.drawable.rounded_person_remove),
            null,
            tint = if (joined) cs.onSecondaryContainer else cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(if (joined) R.string.voice_joined else R.string.voice_left, ev.name),
            style = Expressive.live(14.sp),
            color = if (joined) cs.onSecondaryContainer else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ParticipantGrid(
    users: List<VoiceUser>,
    selfId: String,
    canUserVoice: Boolean,
    onSelect: (VoiceUser) -> Unit,
) {
    val speakingIds by LinkHub.voiceSpeak.collectAsStateWithLifecycle()
    val avatars by VoiceAvatars.avatars.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        users.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { user ->
                    val isSelf = user.id == selfId
                    ParticipantTile(
                        user = user,
                        isSelf = isSelf,
                        speaking = user.id in speakingIds,
                        avatar = avatars[user.avatar],
                        clickable = !isSelf && canUserVoice,
                        onClick = { onSelect(user) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ParticipantTile(
    user: VoiceUser,
    isSelf: Boolean,
    speaking: Boolean,
    avatar: ImageBitmap?,
    clickable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(
        targetValue = if (speaking) cs.primaryContainer else Layer.pane,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "participantBg",
    )
    val fg = if (speaking) cs.onPrimaryContainer else cs.onSurface
    val mutedFg = if (speaking) cs.onPrimaryContainer.copy(alpha = 0.78f) else cs.onSurfaceVariant

    Surface(
        onClick = {
            if (clickable) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
        },
        enabled = clickable,
        shape = RoundedCornerShape(Radius.row),
        color = bg,
        contentColor = fg,
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                // Speaking lights up with a primary scallop — no pulsing animations.
                StateShape(
                    active = speaking,
                    size = 48.dp,
                    color = if (speaking) cs.primary else Layer.field,
                    on = MaterialShapes.Cookie9Sided,
                ) {
                    if (avatar != null) {
                        Image(
                            bitmap = avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(42.dp).clip(CircleShape),
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.rounded_person),
                            null,
                            tint = if (speaking) cs.onPrimary else cs.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                if (user.mute || user.deaf) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(cs.errorContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (user.deaf) R.drawable.rounded_headset_off else R.drawable.rounded_mic_off),
                            null,
                            tint = cs.onErrorContainer,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(user.name, style = Expressive.title(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val badge = when {
                    isSelf -> stringResource(R.string.voice_you)
                    user.localMute -> stringResource(R.string.voice_user_muted_badge)
                    user.volume != 100 -> "${user.volume}%"
                    else -> null
                }
                if (badge != null) {
                    Text(
                        badge,
                        style = Expressive.live(12.sp),
                        color = if (user.localMute) cs.error else mutedFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantVolumeSheet(user: VoiceUser, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val avatars by VoiceAvatars.avatars.collectAsStateWithLifecycle()
    val avatar = avatars[user.avatar]

    var held by remember(user.id) { mutableStateOf(false) }
    var localVol by remember(user.id) { mutableIntStateOf(user.volume) }
    var settling by remember(user.id) { mutableStateOf(false) }
    var lastSentAt by remember(user.id) { mutableLongStateOf(0L) }

    LaunchedEffect(user.volume, held, settling) {
        if (!held && (!settling || abs(user.volume - localVol) <= 2)) {
            settling = false
            localVol = user.volume
        }
    }
    LaunchedEffect(settling) {
        if (settling) {
            delay(800)
            settling = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = cs.surfaceContainerHigh) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Layer.field), contentAlignment = Alignment.Center) {
                    if (avatar != null) {
                        Image(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(painterResource(R.drawable.rounded_person), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(user.name, style = Expressive.wide(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (user.localMute) stringResource(R.string.voice_user_muted_badge) else "$localVol%",
                        style = Expressive.digitsSoft(16.sp),
                        color = if (user.localMute) cs.error else cs.onSurfaceVariant,
                    )
                }
                if (localVol != 100) {
                    DashedPill(
                        text = stringResource(R.string.voice_user_reset),
                        icon = R.drawable.rounded_restart_alt,
                        height = 40.dp,
                        onClick = {
                            localVol = 100
                            settling = true
                            LinkHub.sendVoiceUserVolume(user.id, 100)
                        },
                    )
                }
            }

            BigSlider(
                value = (localVol / 200f).coerceIn(0f, 1f),
                onValueChange = { f ->
                    held = true
                    val v = (f * 200f).roundToInt().coerceIn(0, 200)
                    localVol = v
                    val now = System.currentTimeMillis()
                    if (now - lastSentAt >= 50) {
                        lastSentAt = now
                        LinkHub.sendVoiceUserVolume(user.id, v)
                    }
                },
                onValueChangeFinished = {
                    LinkHub.sendVoiceUserVolume(user.id, localVol)
                    held = false
                    settling = true
                },
                label = stringResource(R.string.voice_user_volume),
                icon = R.drawable.rounded_volume_up,
                enabled = !user.localMute,
            )

            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.voice_user_mute_me),
                    checked = user.localMute,
                    onCheckedChange = { mute ->
                        haptics.performHapticFeedback(if (mute) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        scope.launch {
                            LinkHub.runVoice(JSONObject().put("a", "userMute").put("id", user.id).put("mute", mute))
                        }
                    },
                    icon = R.drawable.rounded_volume_off,
                    summary = stringResource(R.string.voice_user_mute_me_body),
                )
            }
        }
    }
}

@Composable
private fun IdleMicHero(mic: AudioNode?, hasInputs: Boolean, onPickInput: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    if (mic == null) {
        ExpressiveCard(Layer.pane, cs.onSurface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption(stringResource(R.string.voice_pc_mic), color = cs.onSurfaceVariant)
                Text(stringResource(R.string.voice_no_call), style = Expressive.wide(20.sp))
            }
        }
        return
    }
    val volume = rememberLiveVolume(mic)
    val muted = mic.mute
    val bg by animateColorAsState(
        targetValue = if (muted) cs.errorContainer else cs.tertiaryContainer,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "idleMicBg",
    )
    val fg = if (muted) cs.onErrorContainer else cs.onTertiaryContainer

    ExpressiveCard(bg, fg) {
        Ornament(MaterialShapes.Cookie6Sided, 300.dp, fg, Alignment.TopEnd, x = 120.dp, y = (-110).dp, alpha = 0.08f)
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StateShape(
                    active = muted,
                    size = 56.dp,
                    color = if (muted) cs.error else cs.tertiary,
                    on = MaterialShapes.SoftBurst,
                ) {
                    Icon(
                        painterResource(if (muted) R.drawable.rounded_mic_off else R.drawable.rounded_mic_fill1),
                        null,
                        tint = if (muted) cs.onError else cs.onTertiary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.voice_pc_mic), color = fg.copy(alpha = 0.8f))
                    Text(
                        stringResource(if (muted) R.string.voice_mic_muted else R.string.voice_mic_live),
                        style = Expressive.wide(20.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${mic.name} · ${(volume.value * 100).roundToInt()}%",
                        style = Expressive.live(13.sp),
                        color = fg.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            BigSlider(
                value = volume.value,
                onValueChange = volume.change,
                onValueChangeFinished = volume.finish,
                label = stringResource(R.string.audio_mic),
                height = 36.dp,
                fill = fg,
                track = fg.copy(alpha = 0.18f),
                enabled = !muted,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledPill(
                    text = stringResource(if (muted) R.string.voice_mute else R.string.voice_muted),
                    icon = if (muted) R.drawable.rounded_mic_fill1 else R.drawable.rounded_mic_off,
                    container = if (muted) cs.error else cs.tertiary,
                    content = if (muted) cs.onError else cs.onTertiary,
                    onClick = {
                        haptics.performHapticFeedback(if (muted) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        setMute(mic, !muted)
                    },
                    modifier = Modifier.weight(1f),
                )
                if (hasInputs) {
                    TonalPill(
                        text = stringResource(R.string.voice_pick_input),
                        icon = R.drawable.rounded_keyboard_arrow_down,
                        color = fg.copy(alpha = 0.14f),
                        contentColor = fg,
                        height = 52.dp,
                        onClick = onPickInput,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscordStatusCard(status: String) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (status == "auth_required") stringResource(R.string.voice_discord_auth_body)
                else stringResource(R.string.voice_no_call),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            DiscordStatusActions(status)
        }
    }
}

@Composable
private fun DiscordStatusActions(status: String) {
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == "auth_required") {
            TonalPill(
                text = stringResource(R.string.voice_discord_auth),
                icon = R.drawable.rounded_lock_open_right,
                onClick = { scope.launch { LinkHub.runVoice(JSONObject().put("a", "authorize")) } },
            )
        } else {
            TonalPill(
                text = stringResource(R.string.voice_discord_reconnect),
                icon = R.drawable.rounded_refresh,
                onClick = { scope.launch { LinkHub.runVoice(JSONObject().put("a", "connect")) } },
            )
        }
    }
}

private fun isDiscordAudioApp(name: String): Boolean {
    val low = name.lowercase()
    return "discord" in low || "vesktop" in low || "equibop" in low || "webrtc" in low
}

@Composable
private fun DiscordAudioCard(audio: PcAudio?, onPickOutput: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val discordApps = remember(audio?.apps) { audio?.apps?.filter { isDiscordAudioApp(it.name) }.orEmpty() }
    val out = audio?.output
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (out != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Layer.onPane)
                        .clickable { onPickOutput() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(painterResource(R.drawable.rounded_speaker), null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Caption(stringResource(R.string.voice_pick_output), color = cs.onSurfaceVariant)
                        Text(out.name, style = Expressive.title(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.rounded_keyboard_arrow_down), null, tint = cs.onSurfaceVariant)
                }
            }
            if (discordApps.isEmpty()) {
                Text(
                    stringResource(R.string.voice_discord_app_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                discordApps.forEach { app ->
                    DiscordAppSlider(app)
                }
            }
        }
    }
}

@Composable
private fun DiscordAppSlider(app: AudioNode) {
    val cs = MaterialTheme.colorScheme
    val volume = rememberLiveVolume(app)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledIconButton(
                onClick = { setMute(app, !app.mute) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (app.mute) cs.errorContainer else Layer.onPane,
                    contentColor = if (app.mute) cs.onErrorContainer else cs.onSurface,
                ),
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    painterResource(if (app.mute) R.drawable.rounded_volume_off else R.drawable.rounded_volume_up),
                    stringResource(R.string.audio_mute),
                    Modifier.size(18.dp),
                )
            }
            Text(app.name, style = Expressive.title(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (app.mute) stringResource(R.string.audio_muted) else "${(volume.value * 100).roundToInt()}%",
                style = Expressive.digitsSoft(16.sp),
                color = cs.onSurfaceVariant,
            )
        }
        BigSlider(volume.value, volume.change, volume.finish, app.name, height = 24.dp, enabled = !app.mute)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacySection(items: List<PrivacyUse>) {
    val cs = MaterialTheme.colorScheme
    if (items.isEmpty()) {
        Hint(stringResource(R.string.voice_privacy_none))
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val (icon, kindLabel) = when (item.kind) {
                "camera" -> R.drawable.rounded_videocam to stringResource(R.string.voice_sensor_camera)
                "microphone" -> R.drawable.rounded_mic to stringResource(R.string.voice_sensor_mic)
                else -> R.drawable.rounded_screen_share to stringResource(R.string.voice_sensor_screen)
            }
            val label = if (item.app.isNotEmpty()) "$kindLabel · ${item.app}" else kindLabel
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(cs.secondaryContainer)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(icon), null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Text(label, style = Expressive.title(13.sp), color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun VoiceSettingsGroup(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    CardGroup {
        SwitchRow(
            title = stringResource(R.string.voice_setting),
            checked = enabled,
            onCheckedChange = onToggle,
            icon = R.drawable.rounded_record_voice_over,
            summary = stringResource(R.string.voice_setting_body),
        )
    }
}
