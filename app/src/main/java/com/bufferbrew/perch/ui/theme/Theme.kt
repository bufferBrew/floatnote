package com.bufferbrew.perch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The colour themes the user can pick in Settings → Appearance. Each has a light and a dark
 * version; the system setting decides which one is used. [key] is what's saved, so never rename it.
 */
enum class AppPalette(val key: String) {
    /** Deep indigo and violet, taken from the app icon. */
    Indigo("indigo"),
    /** The earlier warm off-white and ink look. */
    Classic("classic"),
    /** Plain black, white and grey. */
    Mono("mono");

    companion object {
        fun fromKey(key: String?): AppPalette = entries.firstOrNull { it.key == key } ?: Indigo
    }
}

/**
 * The floating note's paper, cycled from the panel's ◐ menu. [Theme] follows the app's palette
 * (dark when the phone is); the others are always light sticky-note colours with dark text, so
 * they read over any app. [key] is what's saved, so never rename it.
 */
enum class NotePaper(val key: String) {
    Theme("theme"),
    Yellow("yellow"),
    Blue("blue"),
    Pink("pink"),
    Mint("mint");

    /** The next colour, wrapping round to [Theme]. */
    fun next(): NotePaper = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): NotePaper = entries.firstOrNull { it.key == key } ?: Theme
    }
}

/** The handful of colours a palette is built from. Every role in the scheme maps to one of these. */
private class Swatch(
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val paper: Color,
    val raised: Color,
    val deep: Color,
    val hairline: Color,
    val ink: Color,
    val inkSoft: Color,
)

// Indigo: the icon. Light values are checked to be at least 4.5:1 for the text drawn on them.
private val IndigoLight = Swatch(
    accent = Color(0xFF6D28D9), onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDE7FF), onAccentSoft = Color(0xFF3B1C7A),
    paper = Color(0xFFF7F5FD), raised = Color(0xFFFFFFFF), deep = Color(0xFFECE8FB), hairline = Color(0xFFDCD6F2),
    ink = Color(0xFF16122E), inkSoft = Color(0xFF5D5880),
)
private val IndigoDark = Swatch(
    accent = Color(0xFFA78BFA), onAccent = Color(0xFF0D0C1D),
    accentSoft = Color(0xFF2E2656), onAccentSoft = Color(0xFFE4DCFF),
    paper = Color(0xFF0D0C1D), raised = Color(0xFF16142B), deep = Color(0xFF221F3D), hairline = Color(0xFF302C52),
    ink = Color(0xFFF1EFFD), inkSoft = Color(0xFFA8A3C9),
)

// Classic: the warm off-white and ink look the app started with.
private val ClassicLight = Swatch(
    accent = Color(0xFF141413), onAccent = Color(0xFFFAF8F4),
    accentSoft = Color(0xFFEFEBE4), onAccentSoft = Color(0xFF141413),
    paper = Color(0xFFFAF8F4), raised = Color(0xFFEFEBE4), deep = Color(0xFFEFEBE4), hairline = Color(0xFFE3DED6),
    ink = Color(0xFF141413), inkSoft = Color(0xFF6B6862),
)
private val ClassicDark = Swatch(
    accent = Color(0xFFF2EFEA), onAccent = Color(0xFF121212),
    accentSoft = Color(0xFF262624), onAccentSoft = Color(0xFFF2EFEA),
    paper = Color(0xFF121212), raised = Color(0xFF1C1C1B), deep = Color(0xFF262624), hairline = Color(0xFF34332F),
    ink = Color(0xFFF2EFEA), inkSoft = Color(0xFFA19C94),
)

// Mono: black, white and greys only.
private val MonoLight = Swatch(
    accent = Color(0xFF000000), onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE8E8E8), onAccentSoft = Color(0xFF000000),
    paper = Color(0xFFFFFFFF), raised = Color(0xFFF5F5F5), deep = Color(0xFFEBEBEB), hairline = Color(0xFFD4D4D4),
    ink = Color(0xFF000000), inkSoft = Color(0xFF5C5C5C),
)
private val MonoDark = Swatch(
    accent = Color(0xFFFFFFFF), onAccent = Color(0xFF000000),
    accentSoft = Color(0xFF262626), onAccentSoft = Color(0xFFFFFFFF),
    paper = Color(0xFF000000), raised = Color(0xFF111111), deep = Color(0xFF1F1F1F), hairline = Color(0xFF2E2E2E),
    ink = Color(0xFFFFFFFF), inkSoft = Color(0xFFA6A6A6),
)

// Paper colours. Ink is near-black on all of them; inkSoft stays above 4.5:1 even on the
// darker "deep" tone, and the palette's light accent (violet or black) is kept for buttons.
private val PaperInk = Color(0xFF1C1A17)
private val PaperInkSoft = Color(0xFF4D4940)

private fun paperSwatch(paper: NotePaper, accent: Swatch): Swatch? {
    val (base, raised, deep, hairline) = when (paper) {
        NotePaper.Theme -> return null
        NotePaper.Yellow -> listOf(0xFFFFF4B8, 0xFFFFF9D9, 0xFFFBE88A, 0xFFE3CC5C)
        NotePaper.Blue -> listOf(0xFFDCEEFF, 0xFFEEF7FF, 0xFFC3E1FB, 0xFF9CC4E8)
        NotePaper.Pink -> listOf(0xFFFFE0EC, 0xFFFFF0F5, 0xFFFBC8DB, 0xFFE8A6BF)
        NotePaper.Mint -> listOf(0xFFDDF5E3, 0xFFEEFAF1, 0xFFC2EBCD, 0xFF9BD1AA)
    }.map { Color(it) }
    return Swatch(
        accent = accent.accent, onAccent = accent.onAccent,
        accentSoft = deep, onAccentSoft = PaperInk,
        paper = base, raised = raised, deep = deep, hairline = hairline,
        ink = PaperInk, inkSoft = PaperInkSoft,
    )
}

private fun swatchesFor(palette: AppPalette): Pair<Swatch, Swatch> = when (palette) {
    AppPalette.Indigo -> IndigoLight to IndigoDark
    AppPalette.Classic -> ClassicLight to ClassicDark
    AppPalette.Mono -> MonoLight to MonoDark
}

private fun Swatch.toScheme(dark: Boolean): ColorScheme =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = inkSoft,
        onSecondary = paper,
        secondaryContainer = deep,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentSoft,
        onTertiaryContainer = onAccentSoft,
        surfaceTint = accent,
        surfaceContainerLowest = paper,
        surfaceContainerLow = raised,
        surfaceContainer = raised,
        surfaceContainerHigh = deep,
        surfaceContainerHighest = hairline,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = deep,
        onSurfaceVariant = inkSoft,
        outline = inkSoft,
        outlineVariant = hairline,
    )

/** Serif for titles, the default sans for everything you type into. */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif),
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
    val SpaceXl = 32.dp
    val Icon = 24.dp
    val NoteCardWidth = 220.dp
    val ReadingWidth = 720.dp
    /** How far a screen slides as it comes in or goes out. Opacity does most of the work. */
    val ScreenSlide = 32.dp
    val CornerM = 12.dp
    val CornerL = 24.dp
    val Hairline = 1.dp
    /** Dragging the floating panel's ◐ this far moves its opacity across the whole range. */
    val OpacityDragRange = 160.dp
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
fun PerchTheme(
    palette: AppPalette = AppPalette.Indigo,
    paper: NotePaper = NotePaper.Theme,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val scheme = remember(palette, paper, systemDark) {
        val (light, darkSwatch) = swatchesFor(palette)
        // A paper colour is always light, whatever the system setting.
        paperSwatch(paper, light)?.toScheme(dark = false)
            ?: (if (systemDark) darkSwatch else light).toScheme(systemDark)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content,
    )
}
