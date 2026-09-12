package com.humblesolutions.finai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * App theme, from the approved design (see [FinAiPalette]).
 *
 * **Not** dynamic colour: the green accent carries the brand and a device
 * wallpaper must not repaint it.
 */
@Composable
fun FinAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Edge to edge draws under the bars, so the icons in them have to
            // contrast with OUR background, not the platform default.
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colors) {
        // The Surface is what supplies LocalContentColor. Without it, every
        // Text that does not name a colour falls back to Compose's default of
        // BLACK — which looked right in light mode by accident and left every
        // heading invisible on the near-black dark ground. Found by running it;
        // no test or build could see it.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onBackground,
            content = content,
        )
    }
}

private val DarkColors = darkColorScheme(
    primary = FinAiPalette.Green,
    onPrimary = FinAiPalette.OnGreen,
    secondary = FinAiPalette.Purple,
    tertiary = FinAiPalette.Amber,
    background = FinAiPalette.DarkGround,
    onBackground = FinAiPalette.DarkText,
    surface = FinAiPalette.DarkGround,
    onSurface = FinAiPalette.DarkText,
    surfaceVariant = FinAiPalette.DarkSurface,
    onSurfaceVariant = FinAiPalette.DarkTextMuted,
    outline = FinAiPalette.DarkBorder,
    outlineVariant = FinAiPalette.DarkBorder,
    error = FinAiPalette.Red,
)

private val LightColors = lightColorScheme(
    primary = FinAiPalette.Green,
    onPrimary = FinAiPalette.OnGreen,
    secondary = FinAiPalette.Purple,
    tertiary = FinAiPalette.Amber,
    background = FinAiPalette.LightGround,
    onBackground = FinAiPalette.LightText,
    surface = FinAiPalette.LightGround,
    onSurface = FinAiPalette.LightText,
    surfaceVariant = FinAiPalette.LightSurface,
    onSurfaceVariant = FinAiPalette.LightTextMuted,
    outline = FinAiPalette.LightBorder,
    outlineVariant = FinAiPalette.LightBorder,
    error = FinAiPalette.Red,
)
