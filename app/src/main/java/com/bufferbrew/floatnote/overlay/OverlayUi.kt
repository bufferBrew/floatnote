package com.bufferbrew.floatnote.overlay

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.bufferbrew.floatnote.ui.editor.NoteEditorContent
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.ui.list.NoteListContent
import com.bufferbrew.floatnote.ui.theme.BubbleColors
import com.bufferbrew.floatnote.ui.theme.Dimens
import kotlin.math.roundToInt

/** Bar heights of the bubble's little waveform, in dp. Static for now; animates once voice lands. */
private val WaveHeights = listOf(8, 14, 20, 14, 8)

/**
 * The minimised state, in the style of Wispr Flow's floating bar: a small dark pill with a
 * waveform. Drag it anywhere; tap to open the panel again.
 */
@Composable
fun OverlayBubble(onTap: () -> Unit, onDrag: (Int, Int) -> Unit, onDragEnd: () -> Unit) {
    val label = stringResource(R.string.open_floating_note)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(BubbleColors.Fill)
            .border(Dimens.Hairline, BubbleColors.Edge, CircleShape)
            .windowDrag(onDrag = onDrag, onDragEnd = onDragEnd, onTap = onTap)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { onTap(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.WaveBarWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WaveHeights.forEach { height ->
                Box(
                    Modifier
                        .width(Dimens.WaveBarWidth)
                        .height(height.dp)
                        .clip(CircleShape)
                        .background(BubbleColors.Content)
                )
            }
        }
    }
}

/**
 * The expanded floating window: a soft rounded card. Hosts the same note list and editor as the
 * full app, so every editing feature works here too; only the frame (grab bar to move, corner to
 * resize, opacity, minimise, close) is new.
 */
@Composable
fun OverlayPanel(
    session: NoteViewModel,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    onOpacityChangeFinished: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onResize: (Int, Int) -> Unit,
    onGeometryDone: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    var showOpacity by remember { mutableStateOf(false) }
    val note = session.note
    val shape = RoundedCornerShape(Dimens.CornerL)

    Surface(
        modifier = Modifier
            .fillMaxSize()
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
                    .padding(start = Dimens.SpaceM, end = Dimens.SpaceM, bottom = Dimens.SpaceL),
            ) {
                PanelHeader(
                    title = note?.title?.ifBlank { stringResource(R.string.untitled) }
                        ?: stringResource(R.string.notes_title),
                    onMove = onMove,
                    onMoveEnd = onGeometryDone,
                    onToggleOpacity = { showOpacity = !showOpacity },
                    onMinimise = onMinimise,
                    onClose = onClose,
                )
                if (showOpacity) {
                    OpacityRow(opacity, onOpacityChange, onOpacityChangeFinished)
                }
                val bodyModifier = Modifier.weight(1f).fillMaxWidth()
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
                    Column(bodyModifier) {
                        TextButton(onClick = session::closeNote) {
                            Text(
                                stringResource(R.string.back_to_notes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NoteEditorContent(
                            note = note,
                            pendingFocus = session.pendingFocus,
                            actions = session,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            ResizeHandle(onResize, onGeometryDone, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun PanelHeader(
    title: String,
    onMove: (Int, Int) -> Unit,
    onMoveEnd: () -> Unit,
    onToggleOpacity: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    val moveLabel = stringResource(R.string.move_window)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The grab bar and title drag the window; the buttons beside them still take taps.
        Column(
            modifier = Modifier
                .weight(1f)
                .windowDrag(onDrag = onMove, onDragEnd = onMoveEnd)
                .semantics { contentDescription = moveLabel }
                .padding(top = Dimens.SpaceS, bottom = Dimens.SpaceXs),
        ) {
            Box(
                Modifier
                    .padding(bottom = Dimens.SpaceS)
                    .size(Dimens.GrabberWidth, Dimens.GrabberHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CircleButton("◐", stringResource(R.string.opacity), onToggleOpacity)
        CircleButton("–", stringResource(R.string.minimise), onMinimise)
        CircleButton("✕", stringResource(R.string.close_floating), onClose)
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
private fun ResizeHandle(onResize: (Int, Int) -> Unit, onResizeEnd: () -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.resize_window)
    Box(
        modifier = modifier
            .size(Dimens.HeaderButton)
            .windowDrag(onDrag = onResize, onDragEnd = onResizeEnd)
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
