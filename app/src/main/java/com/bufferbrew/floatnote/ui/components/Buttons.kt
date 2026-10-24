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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    dense: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        modifier = modifier.defaultMinSize(minHeight = if (dense) Dimens.CompactChipHeight else Dimens.ChipHeight),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(
                    horizontal = if (dense) Dimens.SpaceS + Dimens.SpaceXs else Dimens.SpaceM,
                    vertical = if (dense) Dimens.SpaceXs else Dimens.SpaceS,
                ),
            )
        }
    }
}

/** Small round icon button with a full-size touch target around it. */
@Composable
fun CircleButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    Box(
        modifier = modifier.size(if (dense) Dimens.CompactTouchTarget else Dimens.TouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(if (dense) Dimens.CompactButton else Dimens.HeaderButton)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                // The button says [label]; TalkBack shouldn't also read "◐" or "✕".
                Text(glyph, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}
