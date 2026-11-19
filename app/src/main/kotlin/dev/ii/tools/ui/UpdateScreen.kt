@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.ui.theme.flex
import dev.ii.tools.update.AppUpdate
import dev.ii.tools.update.InlineSpan
import dev.ii.tools.update.MarkdownBlock
import dev.ii.tools.update.ReleaseInfo
import dev.ii.tools.update.UpdateError
import dev.ii.tools.update.UpdateMarkdown
import dev.ii.tools.update.UpdateState
import java.time.Instant
import java.util.Date

@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val state by AppUpdate.state.collectAsStateWithLifecycle()
    val latest = state.latest

    ToolScaffold(
        title = stringResource(R.string.update_title),
        onBack = onBack,
        subtitle = if (latest != null) {
            stringResource(R.string.update_subtitle_available, state.currentVersion, latest.version.toString())
        } else {
            stringResource(R.string.update_subtitle_current, state.currentVersion)
        },
        actions = {
            if (state.checking) {
                LoadingIndicator(Modifier.padding(end = 8.dp).size(32.dp))
            } else {
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        AppUpdate.checkNow(ctx)
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Layer.pane),
                ) {
                    Icon(painterResource(R.drawable.rounded_refresh), stringResource(R.string.update_check_now))
                }
            }
        },
    ) {
        if (state.error != null) {
            item(key = "error") {
                CardGroup {
                    ToolRow(
                        title = stringResource(R.string.update_error_title),
                        summary = updateErrorText(ctx, state),
                        icon = R.drawable.rounded_system_update,
                        tint = Tint.ERROR,
                    )
                }
            }
        }

        if (latest != null) {
            item(key = "hero") {
                UpdateHeroCard(
                    release = latest,
                    currentVersion = state.currentVersion,
                    downloadProgress = state.downloadProgress,
                    onDownload = {
                        if (latest.apkUrl != null) AppUpdate.install(ctx, latest) else openExternalUrl(ctx, latest.htmlUrl)
                    },
                    onViewGitHub = { openExternalUrl(ctx, latest.htmlUrl) },
                )
            }

            state.releases.forEachIndexed { idx, release ->
                val date = formatReleaseDate(ctx, release.publishedAt)
                val header = buildString {
                    append(release.tagName)
                    if (date.isNotEmpty()) append(" · ").append(date)
                }
                item(key = "header_${release.tagName}") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (state.releases.size == 1) stringResource(R.string.update_whats_new) else header,
                            style = Expressive.wide(22.sp),
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.releases.size > 1 && idx == 0) {
                            Pill(stringResource(R.string.update_latest_badge), cs.primaryContainer, cs.onPrimaryContainer)
                        }
                    }
                }
                item(key = "notes_${release.tagName}") {
                    ExpressiveCard(Layer.pane, cs.onSurface, radius = Radius.row) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (release.name.isNotEmpty() &&
                                !release.name.equals(release.tagName, ignoreCase = true) &&
                                !release.name.endsWith(release.version.toString())
                            ) {
                                Text(release.name, style = Expressive.title(17.sp), color = cs.onSurface)
                            }
                            if (release.body.isBlank()) {
                                Text(
                                    stringResource(R.string.update_no_notes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            } else {
                                MarkdownContent(release.body, cs.onSurface)
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "up_to_date") {
                ExpressiveCard(Layer.pane, cs.onSurface) {
                    Column(
                        Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ShapeBox(MaterialShapes.Cookie9Sided, 56.dp, cs.primaryContainer) {
                                Icon(
                                    painterResource(R.drawable.rounded_check),
                                    null,
                                    tint = cs.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.update_up_to_date), style = Expressive.wide(22.sp))
                                Text(
                                    stringResource(R.string.update_subtitle_current, state.currentVersion),
                                    style = Expressive.live(14.sp),
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        FilledPill(
                            text = if (state.checking) stringResource(R.string.update_checking) else stringResource(R.string.update_check_now),
                            onClick = { AppUpdate.checkNow(ctx) },
                            icon = R.drawable.rounded_refresh,
                            enabled = !state.checking,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item(key = "settings_header") { GroupHeader(stringResource(R.string.update_settings_header)) }
        item(key = "settings_group") {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.update_auto_check),
                    checked = state.autoCheck,
                    onCheckedChange = { AppUpdate.setAutoCheck(ctx, it) },
                    icon = R.drawable.rounded_refresh,
                    summary = stringResource(R.string.update_auto_check_body),
                )
                ToolRow(
                    title = stringResource(R.string.update_check_now),
                    summary = formatLastChecked(ctx, state.lastCheckMs),
                    icon = R.drawable.rounded_system_update,
                    onClick = if (state.checking) null else {
                        { AppUpdate.checkNow(ctx) }
                    },
                    trailing = if (state.checking) {
                        { LoadingIndicator(Modifier.size(28.dp)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun UpdateHeroCard(
    release: ReleaseInfo,
    currentVersion: String,
    downloadProgress: Float?,
    onDownload: () -> Unit,
    onViewGitHub: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val meta = remember(release.publishedAt, release.apkSize) {
        val date = formatReleaseDate(ctx, release.publishedAt)
        val size = if (release.apkSize > 0L) Formatter.formatShortFileSize(ctx, release.apkSize) else ""
        listOf(date, size).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    ExpressiveCard(cs.primaryContainer, cs.onPrimaryContainer) {
        Ornament(MaterialShapes.Cookie12Sided, 320.dp, cs.onPrimaryContainer, Alignment.TopEnd, x = 130.dp, y = (-110).dp, alpha = 0.11f)
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ShapeBox(MaterialShapes.Cookie9Sided, 58.dp, cs.primary) {
                    Icon(
                        painterResource(R.drawable.rounded_system_update),
                        null,
                        tint = cs.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Caption(stringResource(R.string.update_available))
                    Text(
                        release.tagName,
                        style = Expressive.xwide(32.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.update_version_transition, currentVersion, release.version.toString()),
                    style = Expressive.title(15.sp),
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = Expressive.live(14.sp),
                        color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledPill(
                    text = if (downloadProgress != null) {
                        stringResource(R.string.update_downloading, (downloadProgress * 100).toInt())
                    } else {
                        stringResource(R.string.update_download)
                    },
                    onClick = onDownload,
                    enabled = downloadProgress == null,
                    icon = R.drawable.rounded_download,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (release.htmlUrl.isNotEmpty()) {
                    DashedPill(
                        text = stringResource(R.string.update_view_github),
                        onClick = onViewGitHub,
                        icon = R.drawable.rounded_open_in_new,
                        outline = cs.onPrimaryContainer,
                        content = cs.onPrimaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Shown on the home screen only when a newer, non-dismissed release exists. */
@Composable
internal fun HomeUpdateCard(
    release: ReleaseInfo,
    onOpenUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val size = remember(release.apkSize) {
        if (release.apkSize > 0L) Formatter.formatShortFileSize(ctx, release.apkSize) else ""
    }
    val summary = listOf(release.tagName, size).filter { it.isNotEmpty() }.joinToString(" · ")

    ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, onClick = onOpenUpdate) {
        Ornament(MaterialShapes.Cookie9Sided, 240.dp, cs.tertiary, Alignment.TopEnd, x = 90.dp, y = (-80).dp, alpha = 0.14f)
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeBox(MaterialShapes.Cookie9Sided, 44.dp, cs.tertiary) {
                    Icon(
                        painterResource(R.drawable.rounded_system_update),
                        null,
                        tint = cs.onTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.update_available))
                    Text(
                        stringResource(R.string.update_card_title, release.version.toString()),
                        style = Expressive.wide(20.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onDismiss()
                    },
                ) {
                    Icon(
                        painterResource(R.drawable.rounded_close),
                        stringResource(R.string.update_dismiss),
                        tint = cs.onTertiaryContainer,
                    )
                }
            }
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = Expressive.live(14.sp),
                    color = cs.onTertiaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledPill(
                    text = stringResource(R.string.update_view_changes),
                    onClick = onOpenUpdate,
                    icon = R.drawable.rounded_system_update,
                    container = cs.tertiary,
                    content = cs.onTertiary,
                    height = 46.dp,
                    modifier = Modifier.weight(1f),
                )
                if (release.apkUrl != null) {
                    DashedPill(
                        text = stringResource(R.string.update_download_short),
                        onClick = { openExternalUrl(ctx, release.apkUrl) },
                        icon = R.drawable.rounded_download,
                        outline = cs.onTertiaryContainer,
                        content = cs.onTertiaryContainer,
                        height = 46.dp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MarkdownContent(markdown: String, textColor: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val blocks = remember(markdown) { UpdateMarkdown.parse(markdown) }
    val codeBg = Layer.field
    val linkColor = cs.primary

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when {
                        block.level <= 1 -> Expressive.xwide(21.sp)
                        block.level == 2 -> Expressive.wide(19.sp)
                        block.level == 3 -> Expressive.wide(17.sp)
                        else -> Expressive.title(15.sp)
                    }
                    Text(
                        text = buildInlineText(block.spans, linkColor, codeBg),
                        style = style,
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineText(block.spans, linkColor, codeBg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
                is MarkdownBlock.Bullet -> {
                    val startPad = (block.indent.coerceAtMost(3) * 14).dp
                    Row(
                        Modifier.fillMaxWidth().padding(start = startPad),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (block.marker != null) {
                            Text(
                                text = block.marker,
                                style = Expressive.title(14.sp),
                                color = cs.primary,
                            )
                        } else {
                            Box(
                                Modifier
                                    .padding(top = 8.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(cs.primary),
                            )
                        }
                        Text(
                            text = buildInlineText(block.spans, linkColor, codeBg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MarkdownBlock.Quote -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Layer.onPane)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = buildInlineText(block.spans, linkColor, codeBg),
                            style = Expressive.live(14.sp),
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(codeBg)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

private fun buildInlineText(spans: List<InlineSpan>, linkColor: Color, codeBg: Color): AnnotatedString {
    val boldFamily = flex(700)
    val italicFamily = flex(400, slant = -10f)
    val boldItalicFamily = flex(700, slant = -10f)
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )

    return buildAnnotatedString {
        for (span in spans) {
            val style = when {
                span.code -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBg,
                    fontSize = 13.sp,
                )
                span.bold && span.italic -> SpanStyle(fontFamily = boldItalicFamily)
                span.bold -> SpanStyle(fontFamily = boldFamily)
                span.italic -> SpanStyle(fontFamily = italicFamily)
                else -> SpanStyle()
            }
            val url = span.linkUrl
            if (url != null) {
                withLink(LinkAnnotation.Url(url, linkStyles)) {
                    withStyle(style) { append(span.text) }
                }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
}

internal fun formatReleaseDate(ctx: Context, iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val ms = Instant.parse(iso).toEpochMilli()
        DateFormat.getMediumDateFormat(ctx).format(Date(ms))
    }.getOrDefault("")
}

internal fun formatLastChecked(ctx: Context, lastCheckMs: Long): String {
    if (lastCheckMs <= 0L) return ctx.getString(R.string.update_last_never)
    val elapsedMs = (System.currentTimeMillis() - lastCheckMs).coerceAtLeast(0L)
    val mins = (elapsedMs / 60_000L).toInt()
    val hours = (elapsedMs / 3_600_000L).toInt()
    return when {
        mins < 1 -> ctx.getString(R.string.update_last_just_now)
        mins < 60 -> ctx.resources.getQuantityString(R.plurals.update_last_minutes, mins, mins)
        hours < 24 -> ctx.resources.getQuantityString(R.plurals.update_last_hours, hours, hours)
        else -> ctx.getString(R.string.update_last_date, DateFormat.getMediumDateFormat(ctx).format(Date(lastCheckMs)))
    }
}

internal fun updateErrorText(ctx: Context, state: UpdateState): String = when (state.error) {
    UpdateError.RATE_LIMITED -> ctx.getString(R.string.update_err_rate_limit)
    UpdateError.HTTP -> ctx.getString(R.string.update_err_http, state.lastHttpCode)
    UpdateError.PARSE -> ctx.getString(R.string.update_err_parse)
    UpdateError.CHECKSUM -> ctx.getString(R.string.update_err_checksum)
    UpdateError.NETWORK, null -> ctx.getString(R.string.update_err_network)
}

internal fun openExternalUrl(ctx: Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(ctx, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
    }
}
