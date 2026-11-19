package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.AppIcons
import dev.ii.tools.link.Symbols

/**
 * The shell names Material Symbols (actions, modes, deck keys). The app carries the
 * ones its own screens use as vectors (see AGENTS.md, *Design reference*); any other
 * is drawn by the PC from its font once and cached ([Symbols]).
 */
@DrawableRes
fun bundledSymbol(symbol: String): Int? = when (symbol) {
    "lock" -> R.drawable.rounded_lock
    "screen_lock_portrait" -> R.drawable.rounded_screen_lock_portrait
    "brightness_empty" -> R.drawable.rounded_brightness_empty
    "music_note" -> R.drawable.rounded_music_note
    "inventory_2" -> R.drawable.rounded_inventory_2
    "tune" -> R.drawable.rounded_tune
    "mic_off" -> R.drawable.rounded_mic_off
    "volume_up" -> R.drawable.rounded_volume_up
    "volume_off" -> R.drawable.rounded_volume_off
    "notifications_active" -> R.drawable.rounded_notifications_active
    "grid_view" -> R.drawable.rounded_grid_view
    "palette" -> R.drawable.rounded_palette
    "folder_open" -> R.drawable.rounded_folder_open
    "work" -> R.drawable.rounded_work
    "sports_esports" -> R.drawable.rounded_sports_esports
    "dashboard" -> R.drawable.rounded_dashboard
    "desktop_windows" -> R.drawable.rounded_desktop_windows
    "smart_toy" -> R.drawable.rounded_smart_toy
    "star" -> R.drawable.rounded_star
    "bolt" -> R.drawable.rounded_bolt
    "apps" -> R.drawable.rounded_apps
    "videocam" -> R.drawable.rounded_videocam
    "photo_library" -> R.drawable.rounded_photo_library
    "memory" -> R.drawable.rounded_memory
    "headphones" -> R.drawable.rounded_headphones
    "watch" -> R.drawable.rounded_watch
    "earbuds" -> R.drawable.rounded_earbuds
    "notifications" -> R.drawable.rounded_notifications
    "arrow_back" -> R.drawable.rounded_arrow_back
    else -> null
}

/** [symbol] as a painter: bundled, cached from the PC, or [fallback] until it arrives. */
@Composable
fun symbolPainter(symbol: String, @DrawableRes fallback: Int = R.drawable.rounded_bolt): Painter {
    bundledSymbol(symbol)?.let { return painterResource(it) }
    val paths by Symbols.paths.collectAsStateWithLifecycle()
    val d = paths[symbol]
    LaunchedEffect(symbol, d == null) { if (d == null) Symbols.want(symbol) }
    if (d == null) return painterResource(fallback)
    val vector = remember(d) { symbolVector(d) }
    return rememberVectorPainter(vector)
}

/** Cached desktop app icon as [ImageBitmap], or null until decoded/received from the PC. */
@Composable
fun appIconBitmap(name: String): ImageBitmap? {
    if (name.isEmpty()) return null
    val icons by AppIcons.icons.collectAsStateWithLifecycle()
    val bmp = icons[name]
    LaunchedEffect(name, bmp == null) { if (bmp == null) AppIcons.want(name) }
    return bmp
}

/** A mask, like the bundled drawables: white fill, tinted by whoever draws it. */
private fun symbolVector(pathData: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f)
        .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.White))
        .build()
