package com.bufferbrew.perch.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bufferbrew.perch.PerchApp
import com.bufferbrew.perch.R
import com.bufferbrew.perch.domain.model.NoteSummary
import com.bufferbrew.perch.overlay.OverlayPrefs.Companion.MIN_OPACITY
import com.bufferbrew.perch.ui.components.CircleButton
import com.bufferbrew.perch.ui.components.PillButton
import com.bufferbrew.perch.ui.components.pageEnter
import com.bufferbrew.perch.ui.editor.NoteEditorContent
import com.bufferbrew.perch.ui.editor.NoteViewModel
import com.bufferbrew.perch.ui.editor.shareNote
import com.bufferbrew.perch.ui.dictionary.DictionaryContent
import com.bufferbrew.perch.ui.history.HistoryContent
import com.bufferbrew.perch.ui.list.NoteListContent
import com.bufferbrew.perch.ui.theme.BubbleColors
import com.bufferbrew.perch.ui.theme.Dimens
import com.bufferbrew.perch.ui.theme.NotePaper
import com.bufferbrew.perch.ui.theme.Motion
import com.bufferbrew.perch.voice.VoiceActions
import com.bufferbrew.perch.voice.VoiceState
import com.bufferbrew.perch.voice.Waveform
import com.bufferbrew.perch.voice.sampleAt
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Resting shape of the bubble's waveform, as fractions of full height. */
private val RestingLevels = listOf(0.12f, 0.35f, 0.75f, 0.35f, 0.12f)
private const val BubbleBars = 5
private const val FocusedNoteBackgroundAlpha = 0.85f

/**
 * The minimised state, in the style of Wispr Flow's floating bar: a small dark pill with a
 * waveform. While recording the bars follow your voice; while transcribing it shows an ellipsis.
 * Drag it anywhere. Tap starts listening; while recording it widens into the record pill's bar:
 * ✕ (throw it away), pause, the waveform (tap it, or ✓, to stop and add it to the note). Long-press
 * opens the panel; a tap never does. A tap while transcribing does nothing, and a tap after a
 * failed upload retries it (the kept clip is not thrown away). Other errors show "!" and a tap
 * records again (the device recognizer is used if Whisper isn't set up).
 */
@Composable
fun OverlayBubble(
    voiceState: () -> VoiceState,
    opacity: Float,
    onRecord: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onPauseToggle: () -> Unit,
    onOpen: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Only the tap's meaning changes with the state; the bars read the level in BubbleWave.
    val kind by remember(voiceState) {
        derivedStateOf {
            when (val state = voiceState()) {
                VoiceState.Idle -> BubbleTap.Record
                is VoiceState.Recording -> BubbleTap.Stop
                VoiceState.Transcribing -> BubbleTap.Busy
                // A kept clip waiting for upload: retry it, since a new recording would discard it.
                is VoiceState.Error -> if (state.canRetry) BubbleTap.Retry else BubbleTap.Record
            }
        }
    }
    // A tap never opens the note; only a long press does.
    val onTap: () -> Unit = when (kind) {
        BubbleTap.Record, BubbleTap.Stop -> onRecord
        BubbleTap.Retry -> onRetry
        BubbleTap.Busy -> ({})
    }
    val label = stringResource(
        when (kind) {
            BubbleTap.Record -> R.string.pill_record
            BubbleTap.Stop -> R.string.bubble_stop
            BubbleTap.Retry -> R.string.voice_retry
            BubbleTap.Busy -> R.string.voice_transcribing
        }
    )
    val openLabel = stringResource(R.string.open_floating_note)
    val drag = Modifier
        .windowDrag(onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd, onTap = onTap, onLongPress = onOpen)
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick(label) { onTap(); true }
            onLongClick(openLabel) { onOpen(); true }
        }
    // Changes only when recording starts, pauses or stops, not with each level sample.
    val controls by remember(voiceState) {
        derivedStateOf { (voiceState() as? VoiceState.Recording)?.let { BarControls(it.canPause, it.paused) } }
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
        val bar = controls
        if (bar != null) {
            RecordingBar(bar, drag, onCancel, onPauseToggle, onDone = onRecord) { BubbleIndicator(voiceState, recording = true) }
        } else {
            Box(drag.fillMaxSize(), contentAlignment = Alignment.Center) {
                BubbleIndicator(voiceState, recording = false)
            }
        }
    }
}

/** Which buttons the recording bar shows. */
private data class BarControls(val canPause: Boolean, val paused: Boolean)

/**
 * The bar shown while recording, by both the bubble and the record pill: ✕ throws the recording
 * away, ❚❚ / ▶ pauses and resumes (Whisper clips only), and ✓ stops and adds it to the note. The
 * buttons sit beside [drag] (the waveform), not inside it, so their taps never start a drag.
 */
@Composable
private fun RowScope.RecordingBar(
    controls: BarControls,
    drag: Modifier,
    onCancel: () -> Unit,
    onPauseToggle: () -> Unit,
    onDone: () -> Unit,
    wave: @Composable () -> Unit,
) {
    RecordPillButton("✕", stringResource(R.string.voice_discard), onCancel)
    if (controls.canPause) {
        RecordPillButton(
            if (controls.paused) "▶" else "❚❚",
            stringResource(if (controls.paused) R.string.voice_resume else R.string.voice_pause),
            onPauseToggle,
        )
    }
    Box(drag.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { wave() }
    RecordPillButton("✓", stringResource(R.string.voice_done), onDone)
}

private enum class BubbleTap { Record, Stop, Retry, Busy }

/** Reads audio samples in the draw pass. Transcription uses a stationary busy indicator. */
@Composable
private fun BubbleIndicator(voiceState: () -> VoiceState, recording: Boolean, pill: Boolean = false) {
    val symbol by remember(voiceState, pill) {
        derivedStateOf {
            when (voiceState()) {
                VoiceState.Transcribing -> "…"
                is VoiceState.Error -> "!"
                VoiceState.Idle -> if (pill) "●" else null
                is VoiceState.Recording -> null
            }
        }
    }
    val glyph = symbol
    if (glyph != null) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleMedium,
            color = BubbleColors.Content,
            modifier = Modifier.clearAndSetSemantics {},
        )
    } else {
        Waveform(
            levelAt = { index ->
                if (recording) (voiceState() as? VoiceState.Recording)?.levels.sampleAt(
                    index = minOf(index, BubbleBars - 1 - index),
                    bars = 3,
                ) else RestingLevels[index]
            },
            color = BubbleColors.Content,
            maxHeight = Dimens.BubbleWaveMaxHeight,
            bars = BubbleBars,
        )
    }
}

/**
 * The floating window shrunk below the note's minimum size: just a record button, in the dark
 * pill style of the bubble (Wispr Flow's bar). Tap records into the open note; drag moves it;
 * long-press goes back to the full panel. While recording it shows ✕ (throw the recording away),
 * pause, the waveform and ✓ (stop and add it to the note); the window widens to fit them.
 */
@Composable
fun OverlayRecordPill(
    voiceState: () -> VoiceState,
    opacity: Float,
    onRecord: () -> Unit,
    onCancel: () -> Unit,
    onPauseToggle: () -> Unit,
    onRestore: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    val state by remember(voiceState) {
        derivedStateOf(structuralEqualityPolicy()) {
            when (val current = voiceState()) {
                is VoiceState.Recording -> current.copy(levels = emptyList(), elapsedMs = 0, partial = "")
                else -> current
            }
        }
    }
    val current = state
    val recordLabel = stringResource(R.string.pill_record)
    val restoreLabel = stringResource(R.string.pill_restore)
    // An error needs the panel's strip (Retry, Settings), so a tap opens it.
    val onTap: (() -> Unit)? = when (current) {
        VoiceState.Idle -> onRecord
        is VoiceState.Error -> onRestore
        else -> null
    }
    val tapLabel = when (current) {
        is VoiceState.Error -> current.message
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
        if (current is VoiceState.Recording) {
            RecordingBar(BarControls(current.canPause, current.paused), drag, onCancel, onPauseToggle, onDone = onRecord) {
                BubbleIndicator(voiceState, recording = true)
            }
        } else {
            Box(drag.fillMaxSize(), contentAlignment = Alignment.Center) {
                BubbleIndicator(voiceState, recording = false, pill = true)
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
    /** What the panel draws at now: [opacity], or fully visible while it's in use. */
    shownOpacity: () -> Float,
    paper: NotePaper,
    onPaperCycle: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onOpacityChangeFinished: () -> Unit,
    onGeometryStart: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onGeometryDone: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PerchApp
    var showAdjust by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDictionary by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    /** ◐ is being dragged up or down: the title shows the opacity instead. */
    var draggingOpacity by remember { mutableStateOf(false) }
    val note = session.note
    val focusedNote = note != null && LocalWindowInfo.current.isWindowFocused
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
                .graphicsLayer { alpha = shownOpacity() }
                .border(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant, shape),
            shape = shape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = if (focusedNote) FocusedNoteBackgroundAlpha else 1f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            // No shadow: the window is exactly the panel's size, so a shadow is cut off at its
            // edges, and it shows through the see-through background as a grey haze. The
            // hairline border separates the panel from the app underneath instead.
        ) {
            Box(Modifier.fillMaxSize()) {
                // Header buttons sit SpaceXs from the edge; text and the body get the same
                // SpaceS on both sides. Reserve the resize handle's entire touch area.
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = Dimens.SpaceXs, top = Dimens.SpaceXs, end = Dimens.SpaceXs, bottom = Dimens.HeaderButton),
                ) {
                    PanelHeader(
                        title = when {
                            draggingOpacity -> stringResource(R.string.opacity_value, (opacity * 100).roundToInt())
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
                        opacity = opacity,
                        onOpacityDrag = {
                            draggingOpacity = true
                            onOpacityChange(it)
                        },
                        onOpacityDragEnd = {
                            draggingOpacity = false
                            onOpacityChangeFinished()
                        },
                        onMinimise = onMinimise,
                        onClose = onClose,
                    )
                    AnimatedVisibility(
                        visible = showAdjust,
                        enter = fadeIn(Motion.fade()),
                        exit = fadeOut(Motion.fade()),
                    ) {
                        Column(
                            Modifier.padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXs),
                            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                        ) {
                            SizeRow(panelSize, onPanelSize)
                            PaperRow(paper, onPaperCycle)
                            OpacityRow(opacity, onOpacityChange, onOpacityChangeFinished)
                        }
                    }
                    if (note != null && showSwitcher) {
                        val notes by session.notes.collectAsState(initial = emptyList())
                        NoteSwitcher(
                            notes = notes,
                            currentId = note.id,
                            onOpen = session::open,
                            onCreate = session::createNote,
                        )
                    }
                    val screen = note?.id ?: when {
                        showHistory -> "history"
                        showDictionary -> "dictionary"
                        else -> "notes"
                    }
                    val bodyModifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = Dimens.SpaceXs, end = Dimens.SpaceXs)
                        .pageEnter(screen, forward = screen != "notes")
                    if (note == null && showHistory) {
                        val history by app.voiceRepository.observeHistory().collectAsState(initial = emptyList())
                        HistoryContent(
                            entries = history,
                            onOpenNote = session::open,
                            onDelete = { id -> app.persistScope.launch { app.voiceRepository.deleteDictation(id) } },
                            onClear = { app.persistScope.launch { app.voiceRepository.clearHistory() } },
                            onUseOriginal = { session.useOriginal(it.noteId, it.text, it.raw ?: it.text) },
                            audioOf = { app.recordings.file(it.audio) },
                            onTranscribeAgain = { app.transcribeAgainOrSay(it) },
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
                        val results by session.searchResults.collectAsState(initial = null as List<NoteSummary>?)
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
                            categoryFilter = session.categoryFilter,
                            onCategoryFilter = { session.categoryFilter = it },
                        )
                    } else key(note.id) {
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
    opacity: Float,
    onOpacityDrag: (Float) -> Unit,
    onOpacityDragEnd: () -> Unit,
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
        OpacityButton(opacity, onToggleAdjust, onOpacityDrag, onOpacityDragEnd)
        CircleButton("–", stringResource(R.string.minimise), onMinimise, dense = true)
        CircleButton("✕", stringResource(R.string.close_floating), onClose, dense = true)
    }
}

/**
 * ◐: a tap opens the size, colour and opacity rows; dragging it up or down sets the opacity
 * directly (up is more solid), so it never needs the slider near the screen edge. The panel
 * previews the opacity while you drag, and the title shows the percentage.
 */
@Composable
private fun OpacityButton(
    opacity: Float,
    onTap: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val current by rememberUpdatedState(opacity)
    val change by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    // Dragging this far changes the opacity across its whole range.
    val fullRangePx = with(LocalDensity.current) { Dimens.OpacityDragRange.toPx() }
    val valueLabel = stringResource(R.string.opacity_value, (opacity * 100).roundToInt())
    Box(
        Modifier
            .pointerInput(fullRangePx) {
                var value = 0f
                detectVerticalDragGestures(
                    onDragStart = { value = current },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { pointer, dy ->
                    pointer.consume()
                    value = (value - dy / fullRangePx * (1f - MIN_OPACITY)).coerceIn(MIN_OPACITY, 1f)
                    change(value)
                }
            }
            .semantics {
                stateDescription = valueLabel
                setProgress { target ->
                    change(target.coerceIn(MIN_OPACITY, 1f))
                    end()
                    true
                }
            },
    ) {
        CircleButton("◐", stringResource(R.string.adjust_window), onTap, dense = true)
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
            .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXs),
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
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

/** One chip showing the note's colour; each tap moves to the next one. */
@Composable
private fun PaperRow(paper: NotePaper, onCycle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.paper_colour),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val name = stringResource(
            when (paper) {
                NotePaper.Theme -> R.string.paper_theme
                NotePaper.Yellow -> R.string.paper_yellow
                NotePaper.Blue -> R.string.paper_blue
                NotePaper.Pink -> R.string.paper_pink
                NotePaper.Mint -> R.string.paper_mint
            }
        )
        PillButton(stringResource(R.string.paper_cycle, name), onCycle, dense = true)
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
            // Clear of the screen edge, where the system's back and edge-panel swipes take touches.
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceS, end = Dimens.SpaceM),
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
