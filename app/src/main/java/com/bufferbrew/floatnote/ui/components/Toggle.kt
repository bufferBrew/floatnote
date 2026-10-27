package com.bufferbrew.floatnote.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's on/off switch. Material's default draws the "off" thumb and border in `outline`,
 * which is our near-invisible hairline colour, so an off switch looked like an empty track.
 * Off uses the secondary ink instead; on is filled with ink, like a selected chip.
 */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onPrimary,
            checkedTrackColor = colors.primary,
            checkedBorderColor = colors.primary,
            uncheckedThumbColor = colors.onSurfaceVariant,
            uncheckedTrackColor = colors.surfaceVariant,
            uncheckedBorderColor = colors.onSurfaceVariant,
        ),
    )
}
