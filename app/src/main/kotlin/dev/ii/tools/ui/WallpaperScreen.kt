package dev.ii.tools.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * An image from the phone as the PC's wallpaper: shared to "Set on PC" ([shared]) or
 * picked here with the system photo picker (no storage permission). The original file
 * goes over the link; the shell files it with the other wallpapers and applies it, and
 * its colours follow.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WallpaperScreen(shared: Uri?, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()

    var image by rememberSaveable { mutableStateOf(shared) }
    var sending by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            image = uri
            done = null
            problem = null
        }
    }
    val preview by produceState<ImageBitmap?>(null, image) { value = image?.let { loadPreview(ctx, it) } }
    val tooLarge = stringResource(R.string.wallpaper_too_large)
    val failed = stringResource(R.string.wallpaper_failed)
    val off = stringResource(R.string.wallpaper_off)

    // Shared from another app: back there once it is set.
    LaunchedEffect(done) {
        if (done != null && shared != null) {
            kotlinx.coroutines.delay(1_200)
            onDone()
        }
    }

    val send = { target: String ->
        val uri = image
        if (uri != null && sending == null) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            sending = target
            progress = 0f
            problem = null
            scope.launch {
                val (name, size) = describe(ctx, uri)
                val why = LinkHub.upload(name, size, { ctx.contentResolver.openInputStream(uri) }, "wallpaper", target) { progress = it }
                sending = null
                if (why == null) {
                    done = target
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    problem = when (why) {
                        "too_large" -> tooLarge
                        "disabled" -> off
                        else -> failed
                    }
                }
            }
        }
    }

    ToolScaffold(title = stringResource(R.string.wallpaper_title), onBack = onBack) {
        val ready = linkGate(pc, status, shell != null)
        if (ready && shell?.caps?.contains("wallpaper") != true) {
            item { Hint(off) }
            return@ToolScaffold
        }

        if (image == null) {
            item {
                CardGroup {
                    ToolRow(
                        title = stringResource(R.string.wallpaper_pick),
                        icon = R.drawable.rounded_photo_library,
                        tint = Tint.PRIMARY,
                        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    var sync by remember { mutableStateOf(dev.ii.tools.link.WallSync.isEnabled(ctx)) }
                    SwitchRow(
                        title = stringResource(R.string.wallsync_title),
                        checked = sync,
                        onCheckedChange = {
                            sync = it
                            dev.ii.tools.link.WallSync.setEnabled(ctx, it)
                        },
                        icon = R.drawable.rounded_desktop_windows,
                        summary = stringResource(R.string.wallsync_summary),
                    )
                    val pending by dev.ii.tools.link.WallSync.pending.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { dev.ii.tools.link.WallSync.refreshPending(ctx) }
                    if (sync && pending) {
                        ToolRow(
                            title = stringResource(R.string.wallsync_apply_title),
                            icon = R.drawable.rounded_wallpaper,
                            summary = stringResource(R.string.wallsync_apply_summary),
                            onClick = { dev.ii.tools.link.WallSync.apply(ctx) },
                        )
                    }
                    val saving by dev.ii.tools.link.WallSync.saving.collectAsStateWithLifecycle()
                    if (shell?.caps?.contains("wallSave") == true) {
                        ToolRow(
                            title = stringResource(R.string.wallsave_title),
                            icon = R.drawable.rounded_download,
                            summary = stringResource(if (saving) R.string.wallsave_saving else R.string.wallsave_summary),
                            onClick = { if (!saving) dev.ii.tools.link.WallSync.requestSave() },
                        )
                    }
                }
            }
            return@ToolScaffold
        }

        preview?.let { bmp ->
            item {
                Image(
                    bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
            }
        }

        item { GroupHeader(stringResource(R.string.wallpaper_set_as)) }
        item {
            CardGroup {
                listOf(
                    Triple("desktop", R.string.wallpaper_desktop, R.drawable.rounded_desktop_windows),
                    Triple("lock", R.string.wallpaper_lock, R.drawable.rounded_lock),
                    Triple("both", R.string.wallpaper_both, R.drawable.rounded_wallpaper),
                ).forEach { (target, title, icon) ->
                    ToolRow(
                        title = stringResource(title),
                        summary = when {
                            sending == target -> "${(progress * 100).roundToInt()}%"
                            done == target -> stringResource(R.string.wallpaper_done)
                            else -> null
                        },
                        icon = icon,
                        tint = if (done == target) Tint.PRIMARY else Tint.SECONDARY,
                        onClick = if (ready && sending == null) {
                            { send(target) }
                        } else null,
                        trailing = when {
                            sending == target -> {
                                { LoadingIndicator(Modifier.size(32.dp)) }
                            }
                            done == target -> {
                                { Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary) }
                            }
                            else -> null
                        },
                    )
                }
            }
        }
        problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }

        if (shared == null) {
            item {
                CardGroup {
                    ToolRow(
                        title = stringResource(R.string.wallpaper_other),
                        icon = R.drawable.rounded_photo_library,
                        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                }
            }
        }
    }
}

/** Display name (with an extension the shell accepts) and byte size of [uri]. */
private suspend fun describe(ctx: Context, uri: Uri): Pair<String, Long> = withContext(Dispatchers.IO) {
    var name = "wallpaper"
    var size = -1L
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size < 0) {
        size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }
    val ext = when (ctx.contentResolver.getType(uri)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }
    if (!Regex("""\.(jpe?g|png|webp|avif|bmp|gif|jxl|heic|heif)$""", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
        name = "$name.$ext"
    }
    name to size
}

/** A screen-sized decode for the preview; the file itself is sent untouched. */
private suspend fun loadPreview(ctx: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            val scale = (longest / 1080).coerceAtLeast(1)
            decoder.setTargetSampleSize(scale)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    }.getOrNull()
}
