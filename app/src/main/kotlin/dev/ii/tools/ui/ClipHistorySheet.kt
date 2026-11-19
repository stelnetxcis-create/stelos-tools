package dev.ii.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.clip.ClipHistory
import dev.ii.tools.link.LinkHub
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Whether the home shows the PC's clipboard history: the PC offers it and the phone has it on. */
@Composable
fun clipHistoryAvailable(): Boolean {
    val ctx = LocalContext.current
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(ClipHistory.isEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        on = ClipHistory.isEnabled(ctx)
        onPauseOrDispose { }
    }
    return on && shell?.caps?.contains("clipHistory") == true
}

/**
 * The home's end card for the PC's clipboard history: the newest entry as a preview and an
 * arrow that raises the whole history in a bottom sheet, like the output picker. Subscribed
 * to the PC's history only while the home shows.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClipHistoryCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val entries by ClipHistory.entries.collectAsStateWithLifecycle()
    val total by ClipHistory.total.collectAsStateWithLifecycle()
    val error by ClipHistory.error.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        LinkHub.subscribeClipHistory(true)
        onPauseOrDispose { LinkHub.subscribeClipHistory(false) }
    }
    val newest = entries.firstOrNull()
    val preview = when {
        error == "locked" -> stringResource(R.string.clip_history_locked)
        newest == null -> stringResource(R.string.clip_history_empty)
        newest.image -> stringResource(R.string.clip_history_image)
        else -> newest.text.trim()
    }
    ExpressiveCard(Layer.pane, cs.onSurface, onClick = {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        open = true
    }) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShapeBox(MaterialShapes.Cookie7Sided, 52.dp, cs.tertiaryContainer) {
                Icon(painterResource(R.drawable.rounded_content_paste), null, tint = cs.onTertiaryContainer, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption(
                    if (total > 0) "${stringResource(R.string.home_clip_title)} · $total" else stringResource(R.string.home_clip_title),
                    color = cs.onSurfaceVariant,
                )
                Text(preview, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    open = true
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.rounded_keyboard_arrow_down), stringResource(R.string.home_clip_open), Modifier.size(24.dp).rotate(180f))
            }
        }
    }
    if (open) ClipHistorySheet(onDismiss = { open = false }, onOpenAll = {
        open = false
        onOpen(Dest.CLIPBOARD)
    })
}

/** The PC's clipboard history in a bottom sheet: search, tap to copy to the phone, hold to copy on the PC. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClipHistorySheet(onDismiss: () -> Unit, onOpenAll: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val entries by ClipHistory.entries.collectAsStateWithLifecycle()
    val total by ClipHistory.total.collectAsStateWithLifecycle()
    val loading by ClipHistory.loading.collectAsStateWithLifecycle()
    val loadingMore by ClipHistory.loadingMore.collectAsStateWithLifecycle()
    val error by ClipHistory.error.collectAsStateWithLifecycle()
    val thumbEpoch by ClipHistory.thumbEpoch.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(note) {
        if (note != null) {
            delay(2_000)
            note = null
        }
    }
    val trimmed = query.trim()
    val shown = remember(entries, trimmed) {
        if (trimmed.isEmpty()) entries else entries.filter { it.text.contains(trimmed, ignoreCase = true) }
    }
    val shownById = remember(shown) { shown.associateBy { it.id } }

    // Thumbnails for the image rows on screen, and the next page near the end of the list.
    LaunchedEffect(listState, shownById, trimmed, entries.size, total) {
        snapshotFlow {
            val info = listState.layoutInfo
            val ids = info.visibleItemsInfo.mapNotNull { item ->
                val id = (item.key as? String)?.removePrefix("clip_")?.takeIf { it != item.key }
                if (id != null && shownById[id]?.image == true) id else null
            }
            ids to ((info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount)
        }.distinctUntilChanged().collect { (ids, bounds) ->
            if (ids.isNotEmpty()) ClipHistory.requestThumbs(ctx, ids)
            if (trimmed.isEmpty() && entries.size < total && bounds.second > 0 && bounds.first >= bounds.second - 6) ClipHistory.loadMore()
        }
    }

    fun pulled(code: String?, ok: Int) {
        haptics.performHapticFeedback(if (code == null) HapticFeedbackType.VirtualKey else HapticFeedbackType.Reject)
        note = when (code) {
            null -> ok
            "locked" -> R.string.clip_history_locked
            "too_large" -> R.string.clip_history_too_large
            else -> R.string.clip_failed
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(stringResource(R.string.clip_history_title), style = Expressive.wide(26.sp))
                    note?.let { Text(stringResource(it), style = Expressive.live(14.sp), color = cs.primary) }
                }
                DashedPill(stringResource(R.string.home_clip_all), onClick = onOpenAll, height = 40.dp, icon = R.drawable.rounded_arrow_forward)
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.clip_history_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.rounded_search), null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.rounded_close), null) } }
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
            when {
                error == "locked" -> Hint(stringResource(R.string.clip_history_locked))
                loading && entries.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(36.dp))
                }
                entries.isEmpty() -> Hint(stringResource(R.string.clip_history_empty))
                shown.isEmpty() -> Hint(stringResource(R.string.clip_history_no_match))
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { "clip_${it.id}" }) { entry ->
                        val thumb = remember(entry.id, thumbEpoch) { if (entry.image) ClipHistory.cachedThumb(entry.id) else null }
                        ClipHistoryRow(
                            entry = entry,
                            thumb = thumb,
                            onTap = { scope.launch { pulled(ClipHistory.pullToPhone(entry), R.string.clip_history_copied) } },
                            onCopyOnPc = { scope.launch { pulled(ClipHistory.copyOnPc(entry), R.string.clip_history_copied_pc) } },
                        )
                    }
                    if (loadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}
