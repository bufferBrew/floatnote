package com.bufferbrew.floatnote.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.overlay.OverlayPrefs.Companion.MIN_OPACITY
import com.bufferbrew.floatnote.ui.editor.NoteEditorContent
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.ui.list.NoteListContent
import com.bufferbrew.floatnote.ui.theme.Dimens
import kotlin.math.roundToInt

/** The minimised state: a round button you can drag anywhere. Tap to open the panel again. */
@Composable
fun OverlayBubble(onTap: () -> Unit, onDrag: (Int, Int) -> Unit, onDragEnd: () -> Unit) {
    val label = stringResource(R.string.open_floating_note)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .windowDrag(onDrag = onDrag, onDragEnd = onDragEnd, onTap = onTap)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { onTap(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("✎", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * The expanded floating window. Hosts the same note list and editor as the full app, so every
 * editing feature works here too; only the frame (drag, resize, opacity, minimise) is new.
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(Dimens.CornerM),
        tonalElevation = Dimens.ElevationPanel,
        shadowElevation = Dimens.ElevationPanel,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
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
                HorizontalDivider()
                val bodyModifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceS)
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
                            Text(stringResource(R.string.back_to_notes))
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
        // Only the title area drags, so the buttons beside it still get their taps.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(Dimens.TouchTarget)
                .windowDrag(onDrag = onMove, onDragEnd = onMoveEnd)
                .semantics { contentDescription = moveLabel }
                .padding(horizontal = Dimens.SpaceM),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeaderButton("◐", stringResource(R.string.opacity), onToggleOpacity)
        HeaderButton("—", stringResource(R.string.minimise), onMinimise)
        HeaderButton("✕", stringResource(R.string.close_floating), onClose)
    }
}

@Composable
private fun HeaderButton(glyph: String, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text(glyph)
    }
}

@Composable
private fun OpacityRow(opacity: Float, onChange: (Float) -> Unit, onChangeFinished: () -> Unit) {
    val label = stringResource(R.string.opacity_value, (opacity * 100).roundToInt())
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = opacity,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = MIN_OPACITY..1f,
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceS),
        )
    }
}

@Composable
private fun ResizeHandle(onResize: (Int, Int) -> Unit, onResizeEnd: () -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.resize_window)
    Box(
        modifier = modifier
            .size(Dimens.TouchTarget)
            .windowDrag(onDrag = onResize, onDragEnd = onResizeEnd)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Text(
            "◢",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Dimens.SpaceXs),
        )
    }
}
