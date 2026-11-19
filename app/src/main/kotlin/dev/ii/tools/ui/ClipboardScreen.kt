package dev.ii.tools.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.clip.ClipHistory
import dev.ii.tools.clip.ClipHistoryEntry
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.clip.ClipWatchService
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Clipboard: receiving the PC's, sending this phone's, and browsing the PC's cliphist history. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun ClipboardScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val historyEntries by ClipHistory.entries.collectAsStateWithLifecycle()
    val historyTotal by ClipHistory.total.collectAsStateWithLifecycle()
    val historyLoading by ClipHistory.loading.collectAsStateWithLifecycle()
    val historyLoadingMore by ClipHistory.loadingMore.collectAsStateWithLifecycle()
    val historyError by ClipHistory.error.collectAsStateWithLifecycle()
    val thumbEpoch by ClipHistory.thumbEpoch.collectAsStateWithLifecycle()

    var receives by remember { mutableStateOf(ClipSync.receives(ctx)) }
    var autoSend by remember { mutableStateOf(ClipSync.autoSend(ctx)) }
    var showHistory by remember { mutableStateOf(ClipHistory.isEnabled(ctx)) }
    // Re-read on every return from Settings, where these may have changed.
    var watching by remember { mutableStateOf(ClipWatchService.isEnabled(ctx)) }
    var restricted by remember { mutableStateOf(ClipWatchService.restricted(ctx)) }
    LifecycleResumeEffect(Unit) {
        receives = ClipSync.receives(ctx)
        autoSend = ClipSync.autoSend(ctx)
        showHistory = ClipHistory.isEnabled(ctx)
        watching = ClipWatchService.isEnabled(ctx)
        restricted = ClipWatchService.restricted(ctx)
        onPauseOrDispose { }
    }

    val hasClipCap = shell?.caps?.contains("clip") == true
    val hasHistoryCap = shell?.caps?.contains("clipHistory") == true
    val historyActive = showHistory && hasHistoryCap

    LifecycleResumeEffect(historyActive) {
        LinkHub.subscribeClipHistory(historyActive)
        onPauseOrDispose { LinkHub.subscribeClipHistory(false) }
    }

    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(result) {
        if (result != null) {
            delay(2_500)
            result = null
        }
    }

    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val shown = remember(historyEntries, trimmed) {
        if (trimmed.isEmpty()) historyEntries
        else historyEntries.filter { it.text.contains(trimmed, ignoreCase = true) }
    }
    val shownById = remember(shown) { shown.associateBy { it.id } }

    LaunchedEffect(listState, shownById, historyActive, trimmed, historyEntries.size, historyTotal) {
        if (!historyActive) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val visibleImageIds = info.visibleItemsInfo.mapNotNull { item ->
                val id = (item.key as? String)?.removePrefix("clip_")?.takeIf { it != item.key }
                if (id != null && shownById[id]?.image == true) id else null
            }
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            visibleImageIds to (lastVisible to info.totalItemsCount)
        }
            .distinctUntilChanged()
            .collect { (visibleImageIds, bounds) ->
                if (visibleImageIds.isNotEmpty()) {
                    ClipHistory.requestThumbs(ctx, visibleImageIds)
                }
                val (lastVisible, totalItems) = bounds
                if (trimmed.isEmpty() && historyEntries.size < historyTotal && totalItems > 0 && lastVisible >= totalItems - 8) {
                    ClipHistory.loadMore()
                }
            }
    }

    val showSnack: (Int) -> Unit = { resId ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(ctx.getString(resId))
        }
    }

    ToolScaffold(
        title = stringResource(R.string.clip_title),
        onBack = onBack,
        listState = listState,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        item { Hint(stringResource(R.string.clip_hint)) }
        // Automatic sending is on but its service is not: the steps, each with its button.
        if (autoSend && !watching) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    if (restricted) {
                        PermissionRow(
                            title = stringResource(R.string.clip_step_restricted),
                            body = stringResource(R.string.clip_step_restricted_body),
                            icon = R.drawable.rounded_lock,
                            granted = false,
                            onGrant = { ctx.startActivity(ClipWatchService.appInfoIntent(ctx)) },
                        )
                    }
                    PermissionRow(
                        title = stringResource(R.string.clip_step_service),
                        body = stringResource(R.string.clip_step_service_body),
                        icon = R.drawable.rounded_content_paste,
                        granted = false,
                        onGrant = { ClipWatchService.openSettings(ctx) },
                    )
                }
            }
        }
        val ready = linkGate(pc, status, shell != null)
        if (ready && !hasClipCap) {
            item { Hint(stringResource(R.string.clip_off)) }
            return@ToolScaffold
        }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.clip_send),
                    summary = result?.let { stringResource(it) },
                    icon = R.drawable.rounded_content_paste,
                    tint = if (result == R.string.clip_sent) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = if (sending) null else {
                        {
                            sending = true
                            scope.launch {
                                result = when (ClipSync.sendCurrent(ctx)) {
                                    null -> R.string.clip_sent
                                    "empty" -> R.string.clip_empty
                                    else -> R.string.clip_failed
                                }
                                sending = false
                            }
                        }
                    },
                    trailing = when {
                        sending -> {
                            { LoadingIndicator(Modifier.size(32.dp)) }
                        }
                        result == R.string.clip_sent -> {
                            { Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary) }
                        }
                        else -> null
                    },
                )
                // On needs the accessibility service: off in Settings, the switch takes you there.
                // Switching on only shows the setup steps above; nothing opens by surprise.
                val toggleAuto = { on: Boolean ->
                    autoSend = on
                    ClipSync.setAutoSend(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.clip_auto),
                    checked = autoSend,
                    onCheckedChange = toggleAuto,
                    icon = R.drawable.rounded_bolt,
                    summary = if (autoSend && !watching) stringResource(R.string.status_needs_setup) else null,
                )
                val toggle = { on: Boolean ->
                    receives = on
                    ClipSync.setReceives(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.clip_receive),
                    checked = receives,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_download,
                )
                val toggleHistory = { on: Boolean ->
                    showHistory = on
                    ClipHistory.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.clip_history_show),
                    checked = showHistory,
                    onCheckedChange = toggleHistory,
                    icon = R.drawable.rounded_history,
                )
            }
        }

        if (ready) {
            item { GroupHeader(stringResource(R.string.clip_history_title)) }
            when {
                !hasHistoryCap -> {
                    item { Hint(stringResource(R.string.clip_history_off)) }
                }
                !showHistory -> {
                    item { Hint(stringResource(R.string.clip_history_hidden)) }
                }
                else -> {
                    item {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.clip_history_search)) },
                            leadingIcon = { Icon(painterResource(R.drawable.rounded_search), null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(painterResource(R.drawable.rounded_close), null)
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Layer.field,
                                unfocusedContainerColor = Layer.field,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    when {
                        historyError == "locked" -> {
                            item {
                                CardGroup {
                                    ToolRow(
                                        title = stringResource(R.string.clip_history_locked),
                                        icon = R.drawable.rounded_lock,
                                        tint = Tint.ERROR,
                                    )
                                }
                            }
                        }
                        historyLoading && historyEntries.isEmpty() -> {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadingIndicator(Modifier.size(36.dp))
                                }
                            }
                        }
                        historyEntries.isEmpty() -> {
                            item { Hint(stringResource(R.string.clip_history_empty)) }
                        }
                        shown.isEmpty() -> {
                            item { Hint(stringResource(R.string.clip_history_no_match)) }
                        }
                        else -> {
                            items(shown, key = { "clip_${it.id}" }) { entry ->
                                val thumb = remember(entry.id, thumbEpoch) {
                                    if (entry.image) ClipHistory.cachedThumb(entry.id) else null
                                }
                                ClipHistoryRow(
                                    entry = entry,
                                    thumb = thumb,
                                    onTap = {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        scope.launch {
                                            when (ClipHistory.pullToPhone(entry)) {
                                                null -> showSnack(R.string.clip_history_copied)
                                                "locked" -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                                    showSnack(R.string.clip_history_locked)
                                                }
                                                "too_large" -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                                    showSnack(R.string.clip_history_too_large)
                                                }
                                                else -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                                    showSnack(R.string.clip_failed)
                                                }
                                            }
                                        }
                                    },
                                    onCopyOnPc = {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        scope.launch {
                                            when (ClipHistory.copyOnPc(entry)) {
                                                null -> showSnack(R.string.clip_history_copied_pc)
                                                "locked" -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                                    showSnack(R.string.clip_history_locked)
                                                }
                                                else -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                                    showSnack(R.string.clip_failed)
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            if (historyLoadingMore) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        LoadingIndicator(Modifier.size(28.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ClipHistoryRow(
    entry: ClipHistoryEntry,
    thumb: ImageBitmap?,
    onTap: () -> Unit,
    onCopyOnPc: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Surface(
            color = Layer.pane,
            contentColor = cs.onSurface,
            shape = RoundedCornerShape(Radius.row),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.row))
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entry.image) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Layer.field),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb,
                                contentDescription = stringResource(R.string.clip_history_image),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                painterResource(R.drawable.rounded_image),
                                contentDescription = null,
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.clip_history_image),
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.text.isNotEmpty()) {
                            Text(
                                text = entry.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (entry.pinned) {
                    ShapeBox(
                        shape = MaterialShapes.Cookie7Sided,
                        size = 28.dp,
                        color = cs.tertiaryContainer,
                    ) {
                        Icon(
                            painterResource(R.drawable.rounded_keep),
                            contentDescription = stringResource(R.string.clip_history_pinned),
                            tint = cs.onTertiaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.clip_history_copy_pc)) },
                leadingIcon = { Icon(painterResource(R.drawable.rounded_computer), null) },
                onClick = {
                    menuOpen = false
                    onCopyOnPc()
                },
            )
        }
    }
}
