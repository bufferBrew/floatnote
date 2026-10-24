package com.bufferbrew.floatnote.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.overlay.OverlayPrefs.Companion.MIN_OPACITY
import com.bufferbrew.floatnote.ui.components.CircleButton
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.editor.NoteEditorContent
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.ui.list.NoteListContent
import com.bufferbrew.floatnote.ui.theme.BubbleColors
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.voice.VoiceActions
import com.bufferbrew.floatnote.voice.VoiceState
import com.bufferbrew.floatnote.voice.Waveform
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Resting shape of the bubble's waveform, as fractions of full height. */
private val RestingLevels = listOf(0.12f, 0.35f, 0.75f, 0.35f, 0.12f)
private const val BubbleBars = 5

/**
 * The minimised state, in the style of Wispr Flow's floating bar: a small dark pill with a
 * waveform. While recording the bars follow your voice; while transcribing they pulse.
 * Drag it anywhere; tap to open the panel again.
 */
@Composable
fun OverlayBubble(
    voiceState: () -> VoiceState,
    opacity: Float,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    val label = stringResource(R.string.open_floating_note)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = opacity }
            .clip(CircleShape)
            .background(BubbleColors.Fill)
            .border(Dimens.Hairline, BubbleColors.Edge, CircleShape)
            .windowDrag(onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd, onTap = onTap)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { onTap(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        BubbleWave(voiceState)
    }
}

/** Its own scope, so only the bars redraw as the voice level changes. */
@Composable
private fun BubbleWave(voiceState: () -> VoiceState) {
    val state = voiceState()
    val levels = when (state) {
        is VoiceState.Recording -> {
            // Mirror the latest samples around the centre bar, like a voice meter.
            val recent = state.levels.takeLast(3).let { List(3 - it.size) { 0f } + it }
            listOf(recent[0], recent[1], recent[2], recent[1], recent[0])
        }
        VoiceState.Transcribing -> pulsingLevels()
        else -> RestingLevels
    }
    Waveform(levels.take(BubbleBars), BubbleColors.Content, Dimens.BubbleWaveMaxHeight, bars = BubbleBars)
}

/** A soft wave travelling across the bars while the clip is being transcribed. */
@Composable
private fun pulsingLevels(): List<Float> {
    val transition = rememberInfiniteTransition(label = "transcribing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "phase",
    )
    return List(BubbleBars) { i -> 0.15f + 0.25f * (1 + sin(phase - i * 0.9f)) }
}

/**
 * The expanded floating window: a slim rounded card. Hosts the same note list and editor as the
 * full app (in their compact style), so every editing feature works here too; only the frame
 * (drag the title to move, corner to resize, size/opacity, minimise, close) is new.
 *
 * Opacity is applied as a graphics layer on this content, never as window alpha (see
 * [OverlayWindow]). Touch targets are 36 dp here instead of 48 dp to keep the panel thin.
 */
@Composable
fun OverlayPanel(
    session: NoteViewModel,
    voiceState: () -> VoiceState,
    voiceActions: VoiceActions,
    panelSize: PanelSize?,
    onPanelSize: (PanelSize) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    onOpacityChangeFinished: () -> Unit,
    onGeometryStart: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onResize: (Int, Int) -> Unit,
    onGeometryDone: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    var showAdjust by remember { mutableStateOf(false) }
    val note = session.note
    val shape = RoundedCornerShape(Dimens.CornerL)

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dimens.CompactTouchTarget) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = opacity }
                .border(Dimens.Hairline, MaterialTheme.colorScheme.outline, shape),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = Dimens.ElevationPanel,
        ) {
            Box(Modifier.fillMaxSize()) {
                // Bottom padding keeps the chip row clear of the resize corner.
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = Dimens.SpaceS, end = Dimens.SpaceXs, bottom = Dimens.SpaceM),
                ) {
                    PanelHeader(
                        title = note?.title?.ifBlank { stringResource(R.string.untitled) }
                            ?: stringResource(R.string.notes_title),
                        onBack = if (note != null) session::closeNote else null,
                        onMoveStart = onGeometryStart,
                        onMove = onMove,
                        onMoveEnd = onGeometryDone,
                        onToggleAdjust = { showAdjust = !showAdjust },
                        onMinimise = onMinimise,
                        onClose = onClose,
                    )
                    if (showAdjust) {
                        SizeRow(panelSize, onPanelSize)
                        OpacityRow(opacity, onOpacityChange, onOpacityChangeFinished)
                    }
                    val bodyModifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = Dimens.SpaceXs, end = Dimens.SpaceXs)
                    if (note == null) {
                        val notes by session.notes.collectAsState(initial = emptyList())
                        NoteListContent(
                            notes = notes,
                            onOpen = session::open,
                            onCreate = session::createNote,
                            onDelete = session::deleteNote,
                            modifier = bodyModifier,
                        )
                    } else {
                        NoteEditorContent(
                            note = note,
                            pendingFocus = session.pendingFocus,
                            actions = session,
                            voiceState = voiceState,
                            voiceActions = voiceActions,
                            modifier = bodyModifier,
                            compact = true,
                        )
                    }
                }
                ResizeHandle(onGeometryStart, onResize, onGeometryDone, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** One slim row: back (in a note), draggable title, then size/opacity, minimise and close. */
@Composable
private fun PanelHeader(
    title: String,
    onBack: (() -> Unit)?,
    onMoveStart: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onMoveEnd: () -> Unit,
    onToggleAdjust: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    val moveLabel = stringResource(R.string.move_window)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            CircleButton("‹", stringResource(R.string.back_to_notes), onBack, dense = true)
        }
        // The title drags the window; the buttons beside it still take taps.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(Dimens.CompactTouchTarget)
                .windowDrag(onDragStart = onMoveStart, onDrag = onMove, onDragEnd = onMoveEnd)
                .semantics { contentDescription = moveLabel }
                .padding(horizontal = Dimens.SpaceXs),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CircleButton("◐", stringResource(R.string.adjust_window), onToggleAdjust, dense = true)
        CircleButton("–", stringResource(R.string.minimise), onMinimise, dense = true)
        CircleButton("✕", stringResource(R.string.close_floating), onClose, dense = true)
    }
}

@Composable
private fun SizeRow(current: PanelSize?, onSize: (PanelSize) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.panel_size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PanelSize.entries.forEach { size ->
            PillButton(
                text = stringResource(
                    when (size) {
                        PanelSize.Small -> R.string.size_small
                        PanelSize.Medium -> R.string.size_medium
                        PanelSize.Large -> R.string.size_large
                    }
                ),
                onClick = { onSize(size) },
                selected = size == current,
                dense = true,
            )
        }
    }
}

@Composable
private fun OpacityRow(opacity: Float, onChange: (Float) -> Unit, onChangeFinished: () -> Unit) {
    val label = stringResource(R.string.opacity_value, (opacity * 100).roundToInt())
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Slider(
            value = opacity,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = MIN_OPACITY..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.surfaceVariant,
            ),
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceS),
        )
    }
}

@Composable
private fun ResizeHandle(
    onResizeStart: () -> Unit,
    onResize: (Int, Int) -> Unit,
    onResizeEnd: () -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(R.string.resize_window)
    Box(
        modifier = modifier
            .size(Dimens.HeaderButton)
            .windowDrag(onDragStart = onResizeStart, onDrag = onResize, onDragEnd = onResizeEnd)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            Modifier
                .padding(Dimens.SpaceS)
                .size(Dimens.GrabberWidth / 3, Dimens.GrabberWidth / 3)
                .clip(RoundedCornerShape(topStart = Dimens.CornerL))
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}
