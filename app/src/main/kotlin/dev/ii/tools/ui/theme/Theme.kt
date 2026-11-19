package dev.ii.tools.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Material You colours from the wallpaper, Google Sans Flex, Material 3 Expressive motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IiToolsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = IiTypography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
