package dev.ii.tools.ui

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What arrived from the share sheet. */
data class Shared(val text: String?, val file: Uri?, val mime: String?) {
    val url: String? = text?.let { URL.find(it)?.value }
    /** The whole text is one link (not a quote that contains one). */
    val isLink: Boolean = url != null && text?.trim() == url
    val isImage: Boolean = file != null && mime?.startsWith("image/") == true

    companion object {
        private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

        fun from(intent: Intent?): Shared? {
            if (intent?.action != Intent.ACTION_SEND) return null
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            // A page shared from a browser: its title as the subject, the link as the text.
            val text = listOf(subject, body).filter { it.isNotEmpty() }.distinct().joinToString("\n").ifEmpty { null }
            val file = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            if (text == null && file == null) return null
            return Shared(text, file, intent.type)
        }
    }
}

private data class Target(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int)

/**
 * "Send to PC": a link opens in the PC's browser or goes to its AI chat; text becomes a
 * note, a reminder or a task; a photo or file goes to Downloads (as KDE Connect does),
 * the file shelf, or (images) the AI chat. Files always land in ~/Downloads.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareScreen(shared: Shared?, onBack: () -> Unit, onDone: () -> Unit, direct: Boolean = false) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()

    var sending by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.wallpaper_failed)
    val tooLarge = stringResource(R.string.share_too_large)
    val off = stringResource(R.string.share_off)

    LaunchedEffect(done) {
        if (done != null) {
            delay(1_000)
            onDone()
        }
    }

    val targets = when {
        shared == null -> emptyList()
        shared.file != null && shared.isImage -> listOf(
            Target("downloads", R.string.share_downloads, R.drawable.rounded_download),
            Target("clip", R.string.share_clip, R.drawable.rounded_content_paste),
            Target("ai", R.string.share_ai, R.drawable.rounded_smart_toy),
            Target("shelf", R.string.share_shelf, R.drawable.rounded_inventory),
        )
        shared.file != null -> listOf(
            Target("downloads", R.string.share_downloads, R.drawable.rounded_download),
            Target("shelf", R.string.share_shelf, R.drawable.rounded_inventory),
        )
        shared.isLink -> listOf(
            Target("browser", R.string.share_browser, R.drawable.rounded_open_in_browser),
            Target("clip", R.string.share_clip, R.drawable.rounded_content_paste),
            Target("ai", R.string.share_ai, R.drawable.rounded_smart_toy),
            Target("note", R.string.share_note, R.drawable.rounded_sticky_note_2),
        )
        else -> listOfNotNull(
            Target("clip", R.string.share_clip, R.drawable.rounded_content_paste),
            Target("note", R.string.share_note, R.drawable.rounded_sticky_note_2),
            Target("reminder", R.string.share_reminder, R.drawable.rounded_alarm),
            Target("task", R.string.share_task, R.drawable.rounded_task_alt),
            Target("ai", R.string.share_ai, R.drawable.rounded_smart_toy),
            shared.url?.let { Target("browser", R.string.share_browser, R.drawable.rounded_open_in_browser) },
        )
    }

    val send = send@{ target: String ->
        val s = shared ?: return@send
        if (sending != null || done != null) return@send
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        sending = target
        progress = 0f
        problem = null
        scope.launch {
            val why = if (target == "clip") {
                if (s.file != null) ClipSync.sendImage(ctx, s.file, s.mime ?: "image/jpeg") else ClipSync.sendText(s.text.orEmpty())
            } else if (s.file != null) {
                val (name, size) = describeFile(ctx, s.file, s.mime)
                LinkHub.upload(name, size, { ctx.contentResolver.openInputStream(s.file) }, "share", target) { progress = it }
            } else {
                val text = if (target == "browser") s.url.orEmpty() else s.text.orEmpty()
                LinkHub.runWhenReady("share", JSONObject().put("to", target).put("text", text))
            }
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

    // Picked from the share sheet's top row: a file needs no choice of where.
    LaunchedEffect(direct, status, shell) {
        if (direct && shared?.file != null && status is LinkStatus.Online && shell?.caps?.contains("share") == true)
            send("downloads")
    }

    ToolScaffold(title = stringResource(R.string.share_title), onBack = onBack) {
        val ready = linkGate(pc, status, shell != null)
        if (ready && shell?.caps?.contains("share") != true) {
            item { Hint(off) }
            return@ToolScaffold
        }
        if (shared == null) {
            item { Hint(stringResource(R.string.share_nothing)) }
            return@ToolScaffold
        }

        item { SharedPreview(shared) }
        item { GroupHeader(stringResource(R.string.share_send_as)) }
        item {
            CardGroup {
                targets.forEach { t ->
                    ToolRow(
                        title = stringResource(t.label),
                        summary = when {
                            done == t.id -> stringResource(R.string.share_done)
                            sending == t.id && progress > 0f -> stringResource(R.string.share_progress, (progress * 100).toInt())
                            else -> null
                        },
                        icon = t.icon,
                        tint = if (done == t.id) Tint.PRIMARY else Tint.SECONDARY,
                        onClick = if (ready && sending == null && done == null) {
                            { send(t.id) }
                        } else null,
                        trailing = when {
                            sending == t.id -> {
                                { LoadingIndicator(Modifier.size(32.dp)) }
                            }
                            done == t.id -> {
                                { Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary) }
                            }
                            else -> null
                        },
                    )
                }
            }
        }
        problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        if (shared.file != null) item { CardGroup { TransferModeRow() } }
    }
}

/** The image itself, else the file's name, else the text (a few lines). */
@Composable
private fun SharedPreview(shared: Shared) {
    val ctx = LocalContext.current
    if (shared.isImage && shared.file != null) {
        val bmp by produceState<ImageBitmap?>(null, shared.file) { value = loadThumb(ctx, shared.file) }
        bmp?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(24.dp)),
            )
        }
        return
    }
    if (shared.file != null) {
        val name by produceState("", shared.file) { value = describeFile(ctx, shared.file, shared.mime).first }
        CardGroup { ToolRow(title = name, icon = R.drawable.rounded_inventory) }
        return
    }
    CardGroup {
        ToolBlock {
            Text(
                shared.text.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private suspend fun describeFile(ctx: Context, uri: Uri, mime: String?): Pair<String, Long> = withContext(Dispatchers.IO) {
    var name = "file"
    var size = -1L
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    if (size < 0) size = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0L
    // An image without an extension would be refused by the AI chat on the PC.
    if (mime?.startsWith("image/") == true && !name.contains('.')) name += "." + mime.substringAfter('/').replace("jpeg", "jpg")
    name to size
}

private suspend fun loadThumb(ctx: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / 1080).coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    }.getOrNull()
}
