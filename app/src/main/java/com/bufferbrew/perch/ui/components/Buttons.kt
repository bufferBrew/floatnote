package com.bufferbrew.perch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bufferbrew.perch.ui.theme.Dimens
import com.bufferbrew.perch.ui.theme.Motion

/**
 * Soft rounded chip, the app's main control. [selected] fills it with the accent.
 * Keyboard focus draws a ring at the edge (never a layout shift), in a colour that stays
 * visible on the filled chip.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dense: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) colors.primary else colors.surfaceVariant,
            contentColor = if (selected) colors.onPrimary else colors.onSurface,
        ),
        interactionSource = interactionSource,
        contentPadding = PaddingValues(
            horizontal = if (dense) Dimens.SpaceS + Dimens.SpaceXs else Dimens.SpaceM,
            vertical = if (dense) Dimens.SpaceXs else Dimens.SpaceS,
        ),
        modifier = modifier
            .defaultMinSize(minHeight = if (dense) Dimens.CompactChipHeight else Dimens.TouchTarget)
            .then(
                if (focused && enabled) Modifier.border(
                    Dimens.FocusRing,
                    if (selected) colors.onPrimary else colors.primary,
                    CircleShape,
                ) else Modifier,
            ),
    ) {
        Text(text, style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
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
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .size(if (dense) Dimens.CompactTouchTarget else Dimens.TouchTarget)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(if (dense) Dimens.CompactButton else Dimens.HeaderButton)
                .background(colors.surfaceVariant, CircleShape)
                .then(if (focused) Modifier.border(Dimens.FocusRing, colors.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                color = colors.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** Bring in the current page without keeping its predecessor interactive during the transition. */
@Composable
fun Modifier.pageEnter(page: Any, forward: Boolean = true): Modifier {
    val progress = remember(page) { Animatable(0f) }
    val distance = with(LocalDensity.current) { Dimens.SpaceS.toPx() } * if (forward) 1f else -1f
    LaunchedEffect(progress) { progress.animateTo(1f, Motion.enter()) }
    return graphicsLayer {
        alpha = progress.value
        translationY = distance * (1f - progress.value)
    }
}
