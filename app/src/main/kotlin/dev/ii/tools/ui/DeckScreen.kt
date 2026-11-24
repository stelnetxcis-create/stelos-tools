package dev.ii.tools.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.deck.DeckKey
import dev.ii.tools.deck.DeckStore
import dev.ii.tools.link.DeckAction
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Gap = 8.dp
private val MinKey = 88.dp

private val FolderSymbols = listOf(
    "folder", "folder_open", "work", "sports_esports", "music_note", "palette",
    "dashboard", "desktop_windows", "smart_toy", "star", "bolt", "tune",
    "apps", "videocam", "photo_library", "memory", "headphones", "notifications",
)

private sealed interface DeckSlot {
    val slotId: String
    data object Back : DeckSlot { override val slotId = "+back" }
    data class Key(val item: DeckKey) : DeckSlot { override val slotId get() = item.key }
    data object Add : DeckSlot { override val slotId = "+add" }
}

/**
 * The phone as a stream deck: keys and one-level folders the user picked, cut into
 * pages that fit the screen. Toggle keys show the shell's real state. Edit mode adds,
 * removes, moves between folders and (long press, drag) reorders; dock mode turns the
 * phone sideways, keeps the screen on and dims it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(
    onBack: () -> Unit,
    dock: Boolean,
    onDock: (Boolean) -> Unit,
    initialFolder: String? = null,
    folderNonce: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val catalog by LinkHub.deck.collectAsStateWithLifecycle()
    val apps by LinkHub.apps.collectAsStateWithLifecycle()
    val keys by DeckStore.keys.collectAsStateWithLifecycle()

    var editing by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var editingFolderRef by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingFolderRef by rememberSaveable { mutableStateOf<String?>(null) }
    var openFolderRef by rememberSaveable { mutableStateOf(initialFolder) }

    LaunchedEffect(folderNonce) {
        if (folderNonce > 0) openFolderRef = initialFolder
    }
    val openFolder = remember(keys, openFolderRef) {
        openFolderRef?.let { ref -> keys.firstOrNull { it.isFolder && it.ref == ref } }
    }
    LaunchedEffect(openFolderRef, openFolder) {
        if (openFolderRef != null && openFolder == null) openFolderRef = null
    }

    BackHandler(openFolder != null) { openFolderRef = null }
    BackHandler(editing) { editing = false }
    BackHandler(dock) { onDock(false) }

    LaunchedEffect(catalog) {
        catalog?.let { list -> DeckStore.seed(list.associate { it.id to (it.name to it.icon) }) }
    }
    LaunchedEffect(apps) {
        apps?.let(DeckStore::updateAppIcons)
    }

    val currentStatus = status
    val currentPc = pc
    val currentShell = shell
    val byId = remember(catalog) { catalog.orEmpty().associateBy { it.id } }
    val ready = currentPc != null && currentStatus is LinkStatus.Online && currentShell != null

    // Profile folders: the PC pushes its focused window only while this deck has one (or
    // a folder is being edited and needs the open windows to pick from).
    val focus by LinkHub.focus.collectAsStateWithLifecycle()
    val hasProfiles = remember(keys) { keys.any { it.isFolder && it.match.isNotEmpty() } }
    val wantFocus = (hasProfiles || creatingFolder || editingFolderRef != null) &&
        ready && shell?.caps?.contains("deckFocus") == true
    DisposableEffect(wantFocus) {
        if (wantFocus) LinkHub.subscribeFocus(true)
        onDispose { if (wantFocus) LinkHub.subscribeFocus(false) }
    }
    var autoFolderRef by remember { mutableStateOf<String?>(null) }
    val focusCls = focus?.cls
    LaunchedEffect(focusCls, hasProfiles, editing) {
        if (focus == null || editing) return@LaunchedEffect
        val hit = keys.firstOrNull { it.isFolder && it.matches(focusCls.orEmpty()) }
        if (hit != null) {
            if (openFolderRef != hit.ref) openFolderRef = hit.ref
            autoFolderRef = hit.ref
        } else {
            // Only a folder the deck opened itself goes back to the main page.
            if (autoFolderRef != null && openFolderRef == autoFolderRef) openFolderRef = null
            autoFolderRef = null
        }
    }
    val folders = remember(keys) { keys.filter { it.isFolder } }
    val activeKeys = openFolder?.keys ?: keys

    Scaffold(
        containerColor = Layer.page,
        topBar = {
            if (!dock) {
                TopAppBar(
                    title = {
                        Text(
                            openFolder?.label ?: stringResource(R.string.deck_title),
                            style = Expressive.xwide(28.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                if (openFolder != null) openFolderRef = null else onBack()
                            },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Layer.pane),
                        ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back)) }
                    },
                    actions = {
                        if (ready) {
                            if (editing && openFolder == null) {
                                IconButton(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    creatingFolder = true
                                }) {
                                    Icon(painterResource(R.drawable.rounded_folder_open), stringResource(R.string.deck_new_folder))
                                }
                            } else if (editing && openFolder != null) {
                                IconButton(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    editingFolderRef = openFolder.ref
                                }) {
                                    Icon(painterResource(R.drawable.rounded_edit), stringResource(R.string.deck_folder_rename))
                                }
                                IconButton(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    deletingFolderRef = openFolder.ref
                                }) {
                                    Icon(painterResource(R.drawable.rounded_delete), stringResource(R.string.deck_folder_delete))
                                }
                            }
                            TonalPill(
                                stringResource(if (editing) R.string.deck_done else R.string.deck_edit),
                                onClick = { editing = !editing },
                                icon = if (editing) R.drawable.rounded_check else R.drawable.rounded_edit,
                            )
                            IconButton(onClick = {
                                editing = false
                                onDock(true)
                            }) {
                                Icon(painterResource(R.drawable.rounded_stay_current_landscape), stringResource(R.string.deck_dock))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Layer.page),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                currentPc == null -> Hint(stringResource(R.string.link_needed), Modifier.padding(top = 16.dp))
                !ready -> Box(Modifier.padding(16.dp)) { CardGroup { StatusRow(currentPc, currentStatus) } }
                currentShell == null || "deck" !in currentShell.caps -> Hint(stringResource(R.string.deck_off), Modifier.padding(top = 16.dp))
                openFolder == null && keys.isEmpty() && !editing -> Hint(stringResource(R.string.deck_empty), Modifier.padding(top = 16.dp))
                else -> DeckPages(
                    keys = activeKeys,
                    openFolder = openFolder,
                    folders = folders,
                    catalog = byId,
                    editing = editing,
                    onAdd = { picking = true },
                    onOpenFolder = { openFolderRef = it },
                    onCloseFolder = { openFolderRef = null },
                    onRenameFolder = { editingFolderRef = it.ref },
                    onDeleteFolder = { deletingFolderRef = it.ref },
                )
            }
            if (dock) {
                IconButton(
                    onClick = { onDock(false) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.rounded_close),
                        stringResource(R.string.deck_exit_dock),
                        tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }

    if (picking) {
        DeckPicker(
            catalog = catalog.orEmpty(),
            onDeck = remember(activeKeys) { activeKeys.map { it.key }.toSet() },
            allowFolders = openFolder == null,
            onPick = { DeckStore.add(it, openFolder?.ref) },
            onNewFolder = {
                picking = false
                creatingFolder = true
            },
            onDismiss = { picking = false },
        )
    }

    if (creatingFolder) {
        FolderEditorDialog(
            initial = null,
            openClasses = focus?.open.orEmpty(),
            onConfirm = { name, icon, match ->
                DeckStore.createFolder(name, icon, match)
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }

    editingFolderRef?.let { ref ->
        val folder = keys.firstOrNull { it.isFolder && it.ref == ref }
        if (folder == null) {
            editingFolderRef = null
        } else {
            FolderEditorDialog(
                initial = folder,
                openClasses = focus?.open.orEmpty(),
                onConfirm = { name, icon, match ->
                    DeckStore.renameFolder(folder.ref, name, icon, match)
                    editingFolderRef = null
                },
                onDismiss = { editingFolderRef = null },
            )
        }
    }

    deletingFolderRef?.let { ref ->
        val folder = keys.firstOrNull { it.isFolder && it.ref == ref }
        if (folder == null) {
            deletingFolderRef = null
        } else {
            DeleteFolderDialog(
                folder = folder,
                onMoveOut = {
                    DeckStore.deleteFolder(folder.ref, moveKeysOut = true)
                    if (openFolderRef == folder.ref) openFolderRef = null
                    deletingFolderRef = null
                },
                onDeleteAll = {
                    DeckStore.deleteFolder(folder.ref, moveKeysOut = false)
                    if (openFolderRef == folder.ref) openFolderRef = null
                    deletingFolderRef = null
                },
                onDismiss = { deletingFolderRef = null },
            )
        }
    }
}

/** The keys cut into pages of whatever grid fits; back leads inside a folder, add closes edit mode. */
@Composable
private fun DeckPages(
    keys: List<DeckKey>,
    openFolder: DeckKey?,
    folders: List<DeckKey>,
    catalog: Map<String, DeckAction>,
    editing: Boolean,
    onAdd: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onCloseFolder: () -> Unit,
    onRenameFolder: (DeckKey) -> Unit,
    onDeleteFolder: (DeckKey) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val cols = ((maxWidth + Gap) / (MinKey + Gap)).toInt().coerceIn(3, 8)
        val cell = (maxWidth - Gap * (cols - 1)) / cols
        val dots = 24.dp
        val rows = ((maxHeight - dots + Gap) / (cell + Gap)).toInt().coerceAtLeast(1)
        val capacity = cols * rows
        val keyStart = if (openFolder != null) 1 else 0
        val slots: List<DeckSlot> = buildList {
            if (openFolder != null) add(DeckSlot.Back)
            keys.forEach { add(DeckSlot.Key(it)) }
            if (editing) add(DeckSlot.Add)
        }
        val pages = slots.chunked(capacity).ifEmpty { listOf(emptyList()) }
        val pager = rememberPagerState { pages.size }
        var dragging by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(openFolder?.ref) {
            pager.scrollToPage(0)
        }

        Column {
            HorizontalPager(
                state = pager,
                userScrollEnabled = dragging == null,
                modifier = Modifier.weight(1f),
            ) { page ->
                DeckPage(
                    slots = pages[page],
                    allKeys = keys,
                    openFolder = openFolder,
                    folders = folders,
                    page = page,
                    capacity = capacity,
                    keyStart = keyStart,
                    cols = cols,
                    cell = cell,
                    catalog = catalog,
                    editing = editing,
                    dragging = dragging,
                    onDragging = { dragging = it },
                    onAdd = onAdd,
                    onOpenFolder = onOpenFolder,
                    onCloseFolder = onCloseFolder,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                )
            }
            Row(
                Modifier.fillMaxWidth().height(dots),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pages.size > 1) repeat(pages.size) { i ->
                    val cs = MaterialTheme.colorScheme
                    Box(
                        Modifier.size(width = if (i == pager.currentPage) 28.dp else 8.dp, height = 8.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) cs.primary else cs.surfaceContainerHighest),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeckPage(
    slots: List<DeckSlot>,
    allKeys: List<DeckKey>,
    openFolder: DeckKey?,
    folders: List<DeckKey>,
    page: Int,
    capacity: Int,
    keyStart: Int,
    cols: Int,
    cell: androidx.compose.ui.unit.Dp,
    catalog: Map<String, DeckAction>,
    editing: Boolean,
    dragging: String?,
    onDragging: (String?) -> Unit,
    onAdd: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onCloseFolder: () -> Unit,
    onRenameFolder: (DeckKey) -> Unit,
    onDeleteFolder: (DeckKey) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val notes = remember { mutableStateMapOf<String, String>() }
    val lockedNote = stringResource(R.string.action_locked)
    val unavailableNote = stringResource(R.string.deck_unavailable)
    val stepPx = with(LocalDensity.current) { (cell + Gap).toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val currentKeys by rememberUpdatedState(allKeys)
    val folderRef = openFolder?.ref

    // One parent for every key of the page, placed by index: a key keeps its node (and
    // its drag) while the order changes under it, and the others slide to their places.
    Box(Modifier.fillMaxSize()) {
        slots.forEachIndexed { i, slot ->
            key(slot.slotId) {
                val target = IntOffset(((i % cols) * stepPx).roundToInt(), ((i / cols) * stepPx).roundToInt())
                val animated by animateIntOffsetAsState(target, label = "key")
                val held = slot is DeckSlot.Key && slot.item.key == dragging
                val tileModifier = Modifier
                    .zIndex(if (held) 1f else 0f)
                    .offset { if (held) target + IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) else animated }
                    .size(cell)
                    .graphicsLayer {
                        val s = if (held) 1.08f else 1f
                        scaleX = s
                        scaleY = s
                    }
                when (slot) {
                    DeckSlot.Back -> BackKey(
                        modifier = tileModifier,
                        editing = editing,
                        onBack = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onCloseFolder()
                        },
                    )
                    DeckSlot.Add -> AddKey(tileModifier, onAdd)
                    is DeckSlot.Key -> {
                        val item = slot.item
                        var menuOpen by remember { mutableStateOf(false) }
                        val pageBase = page * capacity
                        val minSlotOnPage = maxOf(pageBase, keyStart)
                        val dragAndMenu = if (editing) {
                            Modifier.pointerInput(item.key, cols, page, capacity, keyStart, folderRef) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val dragStart = awaitLongPressOrCancellation(down.id)
                                    if (dragStart != null) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragOffset = Offset.Zero
                                        onDragging(item.key)
                                        try {
                                            drag(dragStart.id) { change ->
                                                val amount = change.positionChange()
                                                change.consume()
                                                dragOffset += amount
                                                val list = currentKeys
                                                val fromKey = list.indexOfFirst { it.key == item.key }
                                                if (fromKey < 0) return@drag
                                                val fromSlot = fromKey + keyStart
                                                val dx = (dragOffset.x / stepPx).roundToInt()
                                                val dy = (dragOffset.y / stepPx).roundToInt()
                                                if (dx == 0 && dy == 0) return@drag
                                                val maxSlotOnPage = minOf((page + 1) * capacity, keyStart + list.size) - 1
                                                val toSlot = (fromSlot + dx + dy * cols).coerceIn(minSlotOnPage, maxSlotOnPage)
                                                val toKey = toSlot - keyStart
                                                if (toKey == fromKey) return@drag
                                                DeckStore.move(fromKey, toKey, folderRef)
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                val (fc, fr) = (fromSlot - pageBase) % cols to (fromSlot - pageBase) / cols
                                                val (tc, tr) = (toSlot - pageBase) % cols to (toSlot - pageBase) / cols
                                                dragOffset -= Offset((tc - fc) * stepPx, (tr - fr) * stepPx)
                                            }
                                        } finally {
                                            onDragging(null)
                                        }
                                    } else {
                                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                        if (up != null && !up.pressed && !up.isConsumed &&
                                            (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                                        ) {
                                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                            menuOpen = true
                                        }
                                    }
                                }
                            }
                        } else Modifier

                        val view = keyView(item, catalog, modes?.list?.map { it.id to it.name }, presets?.list)
                        val on = when (item.kind) {
                            "action" -> item.ref in active
                            "mode" -> modes?.active == item.ref
                            "preset" -> presets?.active == item.ref
                            "workspace" -> workspaces?.active?.toString() == item.ref
                            else -> false
                        }
                        Box(tileModifier.then(dragAndMenu)) {
                            DeckTile(
                                slot = item,
                                label = notes[item.key] ?: view.label,
                                icon = view.icon,
                                on = on,
                                available = view.available,
                                warning = notes[item.key] != null,
                                editing = editing,
                                modifier = Modifier.fillMaxSize(),
                                onRemove = {
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    if (item.isFolder) onDeleteFolder(item)
                                    else DeckStore.remove(item, folderRef)
                                },
                                onTap = {
                                    if (item.isFolder) {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        onOpenFolder(item.ref)
                                    } else {
                                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                        when (item.kind) {
                                            "mode" -> LinkHub.send("mode", item.ref)
                                            "preset" -> LinkHub.send("preset", item.ref)
                                            "workspace" -> item.ref.toIntOrNull()?.let { LinkHub.send("workspace", it) }
                                            else -> scope.launch {
                                                val id = if (item.kind == "app") "app:${item.ref}" else item.ref
                                                val why = LinkHub.run("deck", id) ?: return@launch
                                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                                notes[item.key] = if (why == "locked") lockedNote else unavailableNote
                                                delay(1_800)
                                                notes.remove(item.key)
                                            }
                                        }
                                    }
                                },
                            )

                            if (editing) {
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    if (item.isFolder) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.deck_folder_open)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.rounded_folder_open), null) },
                                            onClick = {
                                                menuOpen = false
                                                onOpenFolder(item.ref)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.deck_folder_rename)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.rounded_edit), null) },
                                            onClick = {
                                                menuOpen = false
                                                onRenameFolder(item)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.deck_folder_delete)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.rounded_delete), null) },
                                            onClick = {
                                                menuOpen = false
                                                onDeleteFolder(item)
                                            },
                                        )
                                    } else {
                                        if (folderRef != null) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.deck_move_to_top)) },
                                                leadingIcon = { Icon(painterResource(R.drawable.rounded_arrow_back), null) },
                                                onClick = {
                                                    menuOpen = false
                                                    DeckStore.moveToFolder(item, folderRef, null)
                                                },
                                            )
                                        }
                                        folders.filter { it.ref != folderRef }.forEach { targetFolder ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.deck_move_to_folder, targetFolder.label)) },
                                                leadingIcon = {
                                                    Icon(
                                                        symbolPainter(targetFolder.icon, fallback = R.drawable.rounded_folder_open),
                                                        null,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                },
                                                onClick = {
                                                    menuOpen = false
                                                    DeckStore.moveToFolder(item, folderRef, targetFolder.ref)
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.deck_remove)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.rounded_close), null) },
                                            onClick = {
                                                menuOpen = false
                                                DeckStore.remove(item, folderRef)
                                            },
                                        )
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

internal data class KeyView(val label: String, val icon: String, val available: Boolean)

/** What a key reads as now: the live catalog when it has the key, else what it was saved as. */
internal fun keyView(k: DeckKey, catalog: Map<String, DeckAction>, modes: List<Pair<String, String>>?, presets: List<String>?): KeyView =
    when (k.kind) {
        "action" -> catalog[k.ref]?.let { KeyView(it.name, it.icon, true) } ?: KeyView(k.label, k.icon, false)
        "mode" -> KeyView(modes?.firstOrNull { it.first == k.ref }?.second ?: k.label, k.icon, modes?.any { it.first == k.ref } != false)
        "preset" -> KeyView(k.label, k.icon, presets?.contains(k.ref) != false)
        else -> KeyView(k.label, k.icon, true)
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeckTile(
    slot: DeckKey,
    label: String,
    icon: String,
    on: Boolean,
    available: Boolean,
    warning: Boolean,
    editing: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier) {
        if (editing) {
            // Still while editing: a plain card, so a long press drags instead of firing.
            Surface(shape = RoundedCornerShape(24.dp), color = Layer.pane, modifier = Modifier.fillMaxSize()) {
                KeyFace(slot, label, icon, Modifier.padding(8.dp))
            }
            Box(
                Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(28.dp).clip(CircleShape)
                    .background(cs.error).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.rounded_close), stringResource(R.string.deck_remove), tint = cs.onError, modifier = Modifier.size(18.dp))
            }
        } else {
            ToggleButton(
                checked = on,
                onCheckedChange = { onTap() },
                enabled = available,
                // Idle a soft round key; on, it squares up in the primary container (shape is state).
                shapes = ToggleButtonShapes(
                    shape = RoundedCornerShape(40.dp),
                    pressedShape = RoundedCornerShape(16.dp),
                    checkedShape = RoundedCornerShape(22.dp),
                ),
                colors = ToggleButtonDefaults.colors(
                    containerColor = if (warning) cs.errorContainer else Layer.pane,
                    contentColor = if (warning) cs.onErrorContainer else cs.onSurface,
                    checkedContainerColor = cs.primaryContainer,
                    checkedContentColor = cs.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                KeyFace(slot, label, icon, Modifier)
            }
        }
    }
}

/** Icon over label; a workspace shows its number, an app its icon (falling back to initial). */
@Composable
internal fun KeyFace(slot: DeckKey, label: String, icon: String, modifier: Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        when (slot.kind) {
            "workspace" -> Text(slot.ref, style = Expressive.digits(40.sp))
            "app" -> {
                val iconName = if (icon.isEmpty() || icon == "apps") slot.ref.removeSuffix(".desktop") else icon
                val bmp = appIconBitmap(iconName)
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Text(label.take(1).uppercase(), style = Expressive.wide(30.sp))
                }
            }
            "folder" -> Icon(
                symbolPainter(icon.ifEmpty { "folder" }, fallback = R.drawable.rounded_folder_open),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
            else -> Icon(symbolPainter(icon), contentDescription = null, modifier = Modifier.size(34.dp))
        }
        Text(
            label,
            style = Expressive.title(13.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackKey(modifier: Modifier, editing: Boolean, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (editing) {
        Surface(
            onClick = onBack,
            shape = RoundedCornerShape(24.dp),
            color = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            modifier = modifier,
        ) {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Icon(painterResource(R.drawable.rounded_arrow_back), contentDescription = null, modifier = Modifier.size(34.dp))
                Text(stringResource(R.string.back), style = Expressive.title(13.sp), textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    } else {
        ToggleButton(
            checked = false,
            onCheckedChange = { onBack() },
            shapes = ToggleButtonShapes(
                shape = RoundedCornerShape(40.dp),
                pressedShape = RoundedCornerShape(16.dp),
                checkedShape = RoundedCornerShape(22.dp),
            ),
            colors = ToggleButtonDefaults.colors(
                containerColor = cs.secondaryContainer,
                contentColor = cs.onSecondaryContainer,
            ),
            contentPadding = PaddingValues(8.dp),
            modifier = modifier,
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Icon(painterResource(R.drawable.rounded_arrow_back), contentDescription = null, modifier = Modifier.size(34.dp))
                Text(stringResource(R.string.back), style = Expressive.title(13.sp), textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AddKey(modifier: Modifier, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onAdd,
        shape = RoundedCornerShape(24.dp),
        color = cs.secondaryContainer,
        contentColor = cs.onSecondaryContainer,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.rounded_add), stringResource(R.string.deck_add), modifier = Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderEditorDialog(
    initial: DeckKey?,
    openClasses: List<String>,
    onConfirm: (name: String, icon: String, match: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var name by remember { mutableStateOf(initial?.label.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon?.ifEmpty { "folder" } ?: "folder") }
    var match by remember { mutableStateOf(initial?.match.orEmpty()) }
    var typed by remember { mutableStateOf("") }
    val choices = (match + openClasses).distinctBy { it.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (initial == null) R.string.deck_new_folder else R.string.deck_folder_rename),
                style = Expressive.wide(20.sp),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.deck_folder_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Layer.field,
                        unfocusedContainerColor = Layer.field,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.deck_folder_icon), style = Expressive.title(13.sp), color = cs.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolderSymbols.forEach { sym ->
                        val selected = sym == icon
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(if (selected) 14.dp else 22.dp))
                                .background(if (selected) cs.primaryContainer else Layer.field)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    icon = sym
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                symbolPainter(sym, fallback = R.drawable.rounded_folder_open),
                                contentDescription = sym,
                                tint = if (selected) cs.onPrimaryContainer else cs.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.deck_folder_apps), style = Expressive.title(13.sp), color = cs.onSurfaceVariant)
                Text(stringResource(R.string.deck_folder_apps_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    choices.forEach { cls ->
                        val on = match.any { it.equals(cls, ignoreCase = true) }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(if (on) 12.dp else 20.dp))
                                .background(if (on) cs.primaryContainer else Layer.field)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    match = if (on) match.filterNot { it.equals(cls, ignoreCase = true) } else match + cls
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(cls, style = MaterialTheme.typography.labelLarge, color = if (on) cs.onPrimaryContainer else cs.onSurface)
                        }
                    }
                }
                TextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text(stringResource(R.string.deck_folder_app_other)) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Layer.field,
                        unfocusedContainerColor = Layer.field,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val c = typed.trim()
                        if (c.isNotEmpty() && match.none { it.equals(c, ignoreCase = true) }) match = match + c
                        typed = ""
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onConfirm(name, icon, match)
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(if (initial == null) R.string.deck_folder_create else R.string.deck_folder_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteFolderDialog(
    folder: DeckKey,
    onMoveOut: () -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deck_folder_delete), style = Expressive.wide(20.sp)) },
        text = {
            if (folder.keys.isNotEmpty()) {
                Text(stringResource(R.string.deck_folder_delete_body, folder.keys.size, folder.label))
            } else {
                Text(stringResource(R.string.deck_folder_delete_empty, folder.label))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (folder.keys.isNotEmpty()) {
                    TextButton(onClick = onMoveOut) {
                        Text(stringResource(R.string.deck_folder_move_out))
                    }
                }
                TextButton(onClick = onDeleteAll) {
                    Text(
                        stringResource(if (folder.keys.isNotEmpty()) R.string.deck_folder_delete_all else R.string.deck_folder_delete),
                        color = cs.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Everything the deck can hold, grouped and searchable; what is already on it is left out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckPicker(
    catalog: List<DeckAction>,
    onDeck: Set<String>,
    allowFolders: Boolean,
    onPick: (DeckKey) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val apps by LinkHub.apps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { LinkHub.requestApps() }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val match = { name: String -> q.isEmpty() || name.lowercase().contains(q) }
    val pick = { k: DeckKey ->
        onPick(k)
        onDismiss()
    }
    val workspaceLabel = stringResource(R.string.remote_workspaces)
    val newFolderLabel = stringResource(R.string.deck_new_folder)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerHigh) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.deck_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_search), null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Layer.field,
                unfocusedContainerColor = Layer.field,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (allowFolders && match(newFolderLabel)) {
                item(key = "new-folder") {
                    CardGroup {
                        ToolRow(
                            title = newFolderLabel,
                            icon = R.drawable.rounded_folder_open,
                            tint = Tint.PRIMARY,
                            onClick = onNewFolder,
                        )
                    }
                }
            }
            val groups = listOf(
                "shell" to R.string.deck_group_shell,
                "capture" to R.string.deck_group_capture,
                "media" to R.string.deck_group_media,
                "windows" to R.string.deck_group_windows,
                "look" to R.string.deck_group_look,
                "discord" to R.string.deck_group_discord,
                "custom" to R.string.deck_group_custom,
            )
            for ((group, title) in groups) {
                val list = catalog.filter { it.group == group && "action:${it.id}" !in onDeck && match(it.name) }
                if (list.isEmpty()) continue
                item(key = "h-$group") { GroupHeader(stringResource(title)) }
                item(key = "g-$group") {
                    CardGroup {
                        list.forEach { a ->
                            ToolRow(
                                title = a.name,
                                painter = symbolPainter(a.icon),
                                tint = if (a.toggle) Tint.PRIMARY else Tint.SECONDARY,
                                onClick = { pick(DeckKey("action", a.id, a.name, a.icon)) },
                            )
                        }
                    }
                }
            }
            modes?.list?.filter { "mode:${it.id}" !in onDeck && match(it.name) }?.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-modes") { GroupHeader(stringResource(R.string.remote_modes)) }
                item(key = "g-modes") {
                    CardGroup {
                        list.forEach { m ->
                            ToolRow(
                                title = m.name,
                                painter = symbolPainter(m.icon, fallback = R.drawable.rounded_tune),
                                tint = Tint.TERTIARY,
                                onClick = { pick(DeckKey("mode", m.id, m.name, m.icon)) },
                            )
                        }
                    }
                }
            }
            presets?.list?.filter { "preset:$it" !in onDeck && match(it) }?.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-presets") { GroupHeader(stringResource(R.string.remote_presets)) }
                item(key = "g-presets") {
                    CardGroup {
                        list.forEach { name ->
                            ToolRow(
                                title = name,
                                icon = R.drawable.rounded_palette,
                                tint = Tint.TERTIARY,
                                onClick = { pick(DeckKey("preset", name, name, "palette")) },
                            )
                        }
                    }
                }
            }
            (1..10).filter { "workspace:$it" !in onDeck && match("$workspaceLabel $it") }.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-ws") { GroupHeader(workspaceLabel) }
                item(key = "g-ws") {
                    CardGroup {
                        list.forEach { n ->
                            ToolRow(
                                title = "$workspaceLabel $n",
                                leading = { Monogram(n.toString(), Tint.SECONDARY) },
                                onClick = { pick(DeckKey("workspace", n.toString(), n.toString(), "grid_view")) },
                            )
                        }
                    }
                }
            }
            apps?.filter { "app:${it.id}" !in onDeck && match(it.name) }?.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-apps") { GroupHeader(stringResource(R.string.deck_group_apps)) }
                item(key = "g-apps") {
                    CardGroup {
                        list.forEach { app ->
                            ToolRow(
                                title = app.name,
                                leading = {
                                    val bmp = appIconBitmap(app.icon)
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(40.dp),
                                        )
                                    } else {
                                        Monogram(app.name.take(1).uppercase(), Tint.PRIMARY)
                                    }
                                },
                                onClick = { pick(DeckKey("app", app.id, app.name, app.icon.ifEmpty { "apps" })) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A letter or number in the row badge, where an icon would go. */
@Composable
private fun Monogram(text: String, tint: Tint) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tint) {
        Tint.PRIMARY -> cs.primaryContainer to cs.onPrimaryContainer
        Tint.TERTIARY -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tint.ERROR -> cs.errorContainer to cs.onErrorContainer
        Tint.SECONDARY -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}
