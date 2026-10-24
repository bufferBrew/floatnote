package com.bufferbrew.floatnote.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.bufferbrew.floatnote.ui.theme.Dimens

/** Soft rounded chip, the app's main control. [selected] fills it with ink. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        modifier = modifier.defaultMinSize(minHeight = Dimens.ChipHeight),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
            )
        }
    }
}

/** Small round icon button with a full-size touch target around it. */
@Composable
fun CircleButton(glyph: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(Dimens.TouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(Dimens.HeaderButton)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(glyph, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
