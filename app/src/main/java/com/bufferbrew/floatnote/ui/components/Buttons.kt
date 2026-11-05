package com.bufferbrew.floatnote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
        modifier = modifier.defaultMinSize(minHeight = if (dense) Dimens.CompactChipHeight else Dimens.TouchTarget),
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
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(if (dense) Dimens.CompactTouchTarget else Dimens.TouchTarget)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(if (dense) Dimens.CompactButton else Dimens.HeaderButton)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
