package com.bufferbrew.floatnote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Palette taken from the app icon: a deep indigo tile, violet accents and lavender-white paper.
// Every colour below is checked to be at least 4.5:1 against the text drawn on it.
private val Violet = Color(0xFF6D28D9)        // light-theme accent, 6.9:1 on Paper
private val VioletLight = Color(0xFFA78BFA)   // dark-theme accent, 7:1 on Tile
private val VioletMist = Color(0xFFEDE7FF)
private val VioletInk = Color(0xFF3B1C7A)

private val Ink = Color(0xFF16122E)
private val InkSoft = Color(0xFF5D5880)
private val Paper = Color(0xFFF7F5FD)
private val PaperDeep = Color(0xFFECE8FB)
private val PaperHairline = Color(0xFFDCD6F2)
private val White = Color(0xFFFFFFFF)

private val Tile = Color(0xFF0D0C1D)
private val TileRaised = Color(0xFF16142B)
private val TileDeep = Color(0xFF221F3D)
private val TileHairline = Color(0xFF302C52)
private val Lavender = Color(0xFFF1EFFD)
private val LavenderSoft = Color(0xFFA8A3C9)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = White,
    primaryContainer = VioletMist,
    onPrimaryContainer = VioletInk,
    secondary = InkSoft,
    onSecondary = Paper,
    secondaryContainer = PaperDeep,
    onSecondaryContainer = Ink,
    tertiary = Violet,
    onTertiary = White,
    tertiaryContainer = VioletMist,
    onTertiaryContainer = VioletInk,
    surfaceTint = Violet,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Paper,
    surfaceContainer = PaperDeep,
    surfaceContainerHigh = PaperDeep,
    surfaceContainerHighest = PaperHairline,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = InkSoft,
    outlineVariant = PaperHairline,
)

private val DarkColors = darkColorScheme(
    primary = VioletLight,
    onPrimary = Tile,
    primaryContainer = TileDeep,
    onPrimaryContainer = Lavender,
    secondary = LavenderSoft,
    onSecondary = Tile,
    secondaryContainer = TileDeep,
    onSecondaryContainer = Lavender,
    tertiary = VioletLight,
    onTertiary = Tile,
    tertiaryContainer = TileDeep,
    onTertiaryContainer = Lavender,
    surfaceTint = VioletLight,
    surfaceContainerLowest = Tile,
    surfaceContainerLow = TileRaised,
    surfaceContainer = TileRaised,
    surfaceContainerHigh = TileDeep,
    surfaceContainerHighest = TileHairline,
    background = Tile,
    onBackground = Lavender,
    surface = TileRaised,
    onSurface = Lavender,
    surfaceVariant = TileDeep,
    onSurfaceVariant = LavenderSoft,
    outline = LavenderSoft,
    outlineVariant = TileHairline,
)

/** Serif for titles, the default sans for everything you type into. */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif),
    )
}

/** The floating bubble is always dark, whatever the theme, so it reads over any app. */
object BubbleColors {
    val Fill = Color(0xFF0D0C1D)
    val Content = Color(0xFFF1EFFD)
    val Edge = Color(0x26FFFFFF)
    /** The ✕ and ✓ circles on the record pill. */
    val ButtonFill = Color(0x1FFFFFFF)
}

/** Spacing and shape tokens. Use these instead of dp literals in UI code. */
object Dimens {
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp
    val SpaceL = 20.dp
    val CornerM = 12.dp
    val CornerL = 24.dp
    val Hairline = 1.dp
    val ElevationPanel = 8.dp
    val TouchTarget = 48.dp
    val ChipHeight = 36.dp
    val HeaderButton = 32.dp
    /** Width of the keyboard focus ring. Drawn outside the control so it never shifts layout. */
    val FocusRing = 2.dp
    // Floating panel: slimmer chrome than the full app.
    val CompactTouchTarget = 36.dp
    val CompactButton = 28.dp
    val CompactChipHeight = 30.dp
    val GrabberWidth = 36.dp
    val GrabberHeight = 4.dp
    val WaveBarWidth = 3.dp
    val WaveMaxHeight = 24.dp
    val BubbleWaveMaxHeight = 18.dp
}

@Composable
fun FloatNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
