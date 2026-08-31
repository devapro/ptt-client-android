package com.github.devapro.pttdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deliberately **not** dynamic colour.
 *
 * Material You would repaint `primary` from the user's wallpaper, but in this app colour is not
 * decoration — it is the readout. Green means the channel is yours, red means you are audible,
 * blue means someone else is. Those meanings have to hold across the app screen, the floating
 * bubble drawn on a raw `Canvas`, the Glance widget and the notification, none of which can
 * follow a wallpaper-derived scheme. A palette that changes per device would also break the one
 * thing this UI is for: recognising the state without reading it.
 */
private val DarkColors = darkColorScheme(
    primary = SignalGreen,
    onPrimary = Ink,
    primaryContainer = InkSurfaceHigh,
    onPrimaryContainer = Chalk,
    secondary = SignalSky,
    onSecondary = Ink,
    secondaryContainer = InkHighest,
    onSecondaryContainer = Chalk,
    tertiary = SignalAmber,
    onTertiary = Ink,
    tertiaryContainer = InkHighest,
    onTertiaryContainer = Chalk,
    background = Ink,
    onBackground = Chalk,
    surface = InkSurface,
    onSurface = Chalk,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = ChalkDim,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurfaceHigh,
    surfaceContainerLowest = InkLowest,
    surfaceContainerLow = InkLow,
    surfaceContainerHighest = InkHighest,
    surfaceDim = Ink,
    surfaceBright = InkHighest,
    surfaceTint = SignalGreen,
    inverseSurface = Chalk,
    inverseOnSurface = Ink,
    inversePrimary = SignalGreen,
    scrim = Color(0xFF000000),
    outline = InkOutline,
    outlineVariant = InkOutline,
    error = ErrorDark,
    onError = Ink,
    errorContainer = ErrorSurfaceDark,
    onErrorContainer = ErrorDark,
)

private val LightColors = lightColorScheme(
    primary = SignalGreen,
    onPrimary = Graphite,
    primaryContainer = PaperSurfaceHigh,
    onPrimaryContainer = Graphite,
    secondary = SignalSky,
    onSecondary = Graphite,
    secondaryContainer = PaperHighest,
    onSecondaryContainer = Graphite,
    tertiary = SignalAmber,
    onTertiary = Graphite,
    tertiaryContainer = PaperHighest,
    onTertiaryContainer = Graphite,
    background = Paper,
    onBackground = Graphite,
    surface = PaperSurface,
    onSurface = Graphite,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = GraphiteDim,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceHigh,
    surfaceContainerLowest = PaperLowest,
    surfaceContainerLow = PaperLow,
    surfaceContainerHighest = PaperHighest,
    surfaceDim = PaperSurfaceHigh,
    surfaceBright = PaperLowest,
    surfaceTint = SignalGreen,
    inverseSurface = Graphite,
    inverseOnSurface = Paper,
    inversePrimary = SignalGreen,
    scrim = Color(0xFF000000),
    outline = PaperOutline,
    outlineVariant = PaperOutline,
    error = ErrorLight,
    onError = PaperSurface,
    errorContainer = ErrorSurfaceLight,
    onErrorContainer = ErrorLight,
)

@Composable
fun PTTdroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
