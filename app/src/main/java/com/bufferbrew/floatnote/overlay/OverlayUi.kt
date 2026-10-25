package com.bufferbrew.floatnote.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bufferbrew.floatnote.FloatNoteApp
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.NoteSummary
import com.bufferbrew.floatnote.overlay.OverlayPrefs.Companion.MIN_OPACITY
import com.bufferbrew.floatnote.ui.components.CircleButton
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.editor.NoteEditorContent
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.ui.editor.shareNote
import com.bufferbrew.floatnote.ui.dictionary.DictionaryContent
import com.bufferbrew.floatnote.ui.history.HistoryContent
import com.bufferbrew.floatnote.ui.list.NoteListContent
import com.bufferbrew.floatnote.ui.theme.BubbleColors
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.voice.VoiceActions
import com.bufferbrew.floatnote.voice.VoiceState
import com.bufferbrew.floatnote.voice.Waveform
import kotlinx.coroutines.launch
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
        // Mirror the latest samples around the centre bar, like a voice meter.
        is VoiceState.Recording -> pillLevels(state)
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
 * The floating window shrunk below the note's minimum size: just a record button, in the dark
 * pill style of the bubble (Wispr Flow's bar). Tap records into the open note; drag moves it;
 * long-press goes back to the full panel. While recording it shows ✕ (throw the recording away),
 * the waveform and ✓ (stop and add it to the note); the window widens to fit them.
 */
@Composable
fun OverlayRecordPill(
    voiceState: () -> VoiceState,
    opacity: Float,
    onRecord: () -> Unit,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Read here, a leaf, so only the pill redraws as the voice level changes.
    val state = voiceState()
    val recordLabel = stringResource(R.string.pill_record)
    val restoreLabel = stringResource(R.string.pill_restore)
    // An error needs the panel's strip (Retry, Settings), so a tap opens it.
    val onTap: (() -> Unit)? = when (state) {
        VoiceState.Idle -> onRecord
        is VoiceState.Error -> onRestore
        else -> null
    }
    val tapLabel = when (state) {
        is VoiceState.Error -> state.message
        VoiceState.Transcribing -> stringResource(R.string.voice_transcribing)
        else -> recordLabel
    }
    val drag = Modifier
        .windowDrag(onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd, onTap = onTap, onLongPress = onRestore)
        .semantics {
            role = Role.Button
            contentDescription = tapLabel
            if (onTap != null) onClick(tapLabel) { onTap(); true }
            onLongClick(restoreLabel) { onRestore(); true }
        }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = opacity }
            .clip(CircleShape)
            .background(BubbleColors.Fill)
            .border(Dimens.Hairline, BubbleColors.Edge, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is VoiceState.Recording) {
            // ✕ and ✓ sit beside the drag area, not inside it, so their taps never start a drag.
            RecordPillButton("✕", stringResource(R.string.voice_discard), onCancel)
            Box(drag.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Waveform(pillLevels(state), BubbleColors.Content, Dimens.BubbleWaveMaxHeight, bars = BubbleBars)
            }
            RecordPillButton("✓", stringResource(R.string.voice_done), onRecord)
        } else {
            Box(drag.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state) {
                    VoiceState.Transcribing -> Waveform(pulsingLevels(), BubbleColors.Content, Dimens.BubbleWaveMaxHeight, bars = BubbleBars)
                    else -> Text(
                        if (state is VoiceState.Error) "!" else "●",
                        style = MaterialTheme.typography.titleMedium,
                        color = BubbleColors.Content,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/** A small round button on the dark pill. */
@Composable
private fun RecordPillButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(Modifier.size(Dimens.CompactTouchTarget), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = BubbleColors.ButtonFill,
            contentColor = BubbleColors.Content,
            modifier = Modifier
                .size(Dimens.CompactButton)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(glyph, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/** The latest samples mirrored around the centre bar, like the bubble. */
private fun pillLevels(state: VoiceState.Recording): List<Float> {
    val recent = state.levels.takeLast(3).let { List(3 - it.size) { 0f } + it }
    return listOf(recent[0], recent[1], recent[2], recent[1], recent[0])
}

/**
 * The expanded floating window: a slim rounded card. Hosts the same note list and editor as the
 * full app (in their compact style), so every editing feature works here too; only the frame
 * (drag the title to move, size/opacity, minimise, close) is new. The resize corner sits on top
 * of it, in [OverlayWindow], so it keeps working when the window shrinks into [OverlayRecordPill].
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
    onGeometryDone: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FloatNoteApp
    var showAdjust by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDictionary by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    val notes by session.notes.collectAsState(initial = emptyList())
    val note = session.note
    // Opening a note (from the switcher, history or list) always leaves the history view.
    LaunchedEffect(note?.id) {
        if (note != null) {
            showHistory = false
            showDictionary = false
        }
    }
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
                        title = when {
                            note != null -> note.title.ifBlank { stringResource(R.string.untitled) }
                            showHistory -> stringResource(R.string.history_title)
                            showDictionary -> stringResource(R.string.vocab_title)
                            else -> stringResource(R.string.notes_title)
                        },
                        onBack = when {
                            note != null -> session::closeNote
                            showHistory -> { { showHistory = false } }
                            showDictionary -> { { showDictionary = false } }
                            else -> null
                        },
                        // In a note, tapping the title shows the quick note switcher.
                        onTitleTap = if (note != null) { { showSwitcher = !showSwitcher } } else null,
                        switcherOpen = showSwitcher,
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
                    if (note != null && showSwitcher) {
                        NoteSwitcher(
                            notes = notes,
                            currentId = note.id,
                            onOpen = session::open,
                            onCreate = session::createNote,
                        )
                    }
                    val bodyModifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = Dimens.SpaceXs, end = Dimens.SpaceXs)
                    if (note == null && showHistory) {
                        val history by app.voiceRepository.observeHistory().collectAsState(initial = emptyList())
                        HistoryContent(
                            entries = history,
                            onOpenNote = session::open,
                            onDelete = { id -> app.persistScope.launch { app.voiceRepository.deleteDictation(id) } },
                            onClear = { app.persistScope.launch { app.voiceRepository.clearHistory() } },
                            onUseOriginal = { session.useOriginal(it.noteId, it.text, it.raw ?: it.text) },
                            modifier = bodyModifier,
                            compact = true,
                        )
                    } else if (note == null && showDictionary) {
                        val vocabulary by app.vocabulary.collectAsState()
                        DictionaryContent(
                            entries = vocabulary,
                            onAdd = { term, replacement ->
                                app.persistScope.launch { app.voiceRepository.addVocab(term, replacement) }
                            },
                            onDelete = { id -> app.persistScope.launch { app.voiceRepository.deleteVocab(id) } },
                            modifier = bodyModifier,
                            compact = true,
                        )
                    } else if (note == null) {
                        val results by session.searchResults.collectAsState(initial = emptyList())
                        NoteListContent(
                            notes = results,
                            onOpen = session::open,
                            onCreate = session::createNote,
                            onDelete = session::deleteNote,
                            modifier = bodyModifier,
                            onHistory = { showHistory = true },
                            onDictionary = { showDictionary = true },
                            compact = true,
                            deletedTitle = session.recentlyDeleted?.title,
                            onUndoDelete = session::undoDelete,
                            onDeletedDismissed = session::clearDeleted,
                            query = session.query,
                            onQueryChange = { session.query = it },
                            onPin = session::setPinned,
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
                            assist = session.assist,
                            canUndo = session.canUndo,
                            canRedo = session.canRedo,
                            onShare = {
                                // The share sheet is an Activity: it would open under the panel.
                                onMinimise()
                                context.shareNote(note)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One slim row: back, draggable title, then size/opacity, minimise and close. In a note the
 * title also takes a tap (anything shorter than a drag) to open the note switcher; ▾ hints at it.
 */
@Composable
private fun PanelHeader(
    title: String,
    onBack: (() -> Unit)?,
    onTitleTap: (() -> Unit)?,
    switcherOpen: Boolean,
    onMoveStart: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onMoveEnd: () -> Unit,
    onToggleAdjust: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    val moveLabel = stringResource(R.string.move_window)
    val switchLabel = stringResource(R.string.switch_note)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            CircleButton("‹", stringResource(R.string.back_to_notes), onBack, dense = true)
        }
        // The title drags the window; the buttons beside it still take taps.
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.CompactTouchTarget)
                .windowDrag(onDragStart = onMoveStart, onDrag = onMove, onDragEnd = onMoveEnd, onTap = onTitleTap)
                .semantics {
                    contentDescription = moveLabel
                    if (onTitleTap != null) onClick(switchLabel) { onTitleTap(); true }
                }
                .padding(horizontal = Dimens.SpaceXs),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onTitleTap != null) {
                    Text(
                        if (switcherOpen) " ▴" else " ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        CircleButton("◐", stringResource(R.string.adjust_window), onToggleAdjust, dense = true)
        CircleButton("–", stringResource(R.string.minimise), onMinimise, dense = true)
        CircleButton("✕", stringResource(R.string.close_floating), onClose, dense = true)
    }
}

/**
 * Quick note switcher: a sideways-scrolling row of recent notes under the header, plus "New".
 * It stays open while you hop between notes; tap the title again to hide it.
 */
@Composable
private fun NoteSwitcher(
    notes: List<NoteSummary>,
    currentId: String,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Dimens.SpaceXs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(stringResource(R.string.switcher_new), onCreate, dense = true)
        val untitled = stringResource(R.string.untitled)
        notes.take(SWITCHER_NOTES).forEach { summary ->
            val label = summary.title.ifBlank { summary.preview.ifBlank { untitled } }
            PillButton(
                text = if (label.length > SWITCHER_LABEL_CHARS) label.take(SWITCHER_LABEL_CHARS) + "…" else label,
                onClick = { onOpen(summary.id) },
                selected = summary.id == currentId,
                dense = true,
            )
        }
    }
}

private const val SWITCHER_NOTES = 12
private const val SWITCHER_LABEL_CHARS = 18

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
internal fun ResizeHandle(
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
                // onSurfaceVariant, not the hairline outline: the grip needs 3:1 to be findable.
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}
