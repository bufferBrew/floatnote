package com.bufferbrew.floatnote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Teal = Color(0xFF2F5D62)
private val TealLight = Color(0xFF8FC1C4)
private val Paper = Color(0xFFF5F1E6)
private val Ink = Color(0xFF1C1B1A)

private val LightColors = lightColorScheme(
    primary = Teal,
    background = Paper,
    surface = Paper,
    onBackground = Ink,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
)

/** Spacing and shape tokens. Use these instead of dp literals in UI code. */
object Dimens {
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp
    val CornerM = 12.dp
    val ElevationPanel = 6.dp
    val TouchTarget = 48.dp
}

@Composable
fun FloatNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
