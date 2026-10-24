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

// Wispr Flow–style palette: monochrome ink on warm off-white, no brand accent colour.
private val Ink = Color(0xFF141413)
private val InkSoft = Color(0xFF6B6862)
private val Cream = Color(0xFFFAF8F4)
private val CreamDeep = Color(0xFFEFEBE4)
private val Hairline = Color(0xFFE3DED6)

private val Night = Color(0xFF121212)
private val NightRaised = Color(0xFF1C1C1B)
private val NightDeep = Color(0xFF262624)
private val Bone = Color(0xFFF2EFEA)
private val BoneSoft = Color(0xFFA19C94)
private val NightHairline = Color(0xFF34332F)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Cream,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = CreamDeep,
    onSurfaceVariant = InkSoft,
    outline = Hairline,
    outlineVariant = Hairline,
)

private val DarkColors = darkColorScheme(
    primary = Bone,
    onPrimary = Night,
    background = Night,
    onBackground = Bone,
    surface = NightRaised,
    onSurface = Bone,
    surfaceVariant = NightDeep,
    onSurfaceVariant = BoneSoft,
    outline = NightHairline,
    outlineVariant = NightHairline,
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
    val Fill = Color(0xFF0E0E0D)
    val Content = Color(0xFFF2EFEA)
    val Edge = Color(0x26FFFFFF)
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
