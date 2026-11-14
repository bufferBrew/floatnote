package com.bufferbrew.perch.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.bufferbrew.perch.R
import com.bufferbrew.perch.domain.model.Block
import com.bufferbrew.perch.domain.model.BlockType
import com.bufferbrew.perch.domain.model.Note
import com.bufferbrew.perch.domain.model.NoteCategory
import com.bufferbrew.perch.ai.labelRes
import com.bufferbrew.perch.ui.components.CategoryChips
import com.bufferbrew.perch.domain.model.TaskCompletionMode
import com.bufferbrew.perch.ui.components.PillButton
import com.bufferbrew.perch.ui.theme.Dimens
import com.bufferbrew.perch.ui.theme.Motion
import com.bufferbrew.perch.voice.VoiceActions
import com.bufferbrew.perch.voice.VoiceState
import com.bufferbrew.perch.voice.VoiceStatus

/**
 * The note editor. Stateless and host-agnostic: the Activity and the floating overlay both
 * render exactly this, so every feature works in both (CLAUDE.md requirement 3).
 */
@Composable
fun NoteEditorContent(
    note: Note,
    pendingFocus: String?,
    actions: NoteEditorActions,
    voiceState: () -> VoiceState,
    voiceActions: VoiceActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    assist: AssistState = AssistState.Idle,
    onShare: (() -> Unit)? = null,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
) {
    // Read voice state only where it's drawn: it changes ~12×/s while listening, and reading it
    // here would redraw the whole editor that often.
    val recording by remember(voiceState) { derivedStateOf { voiceState() is VoiceState.Recording } }
    val haptics = LocalHapticFeedback.current
    val typography = MaterialTheme.typography
    val listState = rememberLazyListState()
    val currentNote by rememberUpdatedState(note)
    // A new line about to take focus may be below the fold: bring it into view so it can.
    LaunchedEffect(pendingFocus) {
        val index = currentNote.blocks.indexOfFirst { it.id == pendingFocus }
        if (index >= 0) listState.animateScrollToItem(index + 2) // +2: the title and category come first
    }
    // Dictation lands at the end. Follow it if you were already looking at the end, as a
    // chat does; don't yank the view away from a line you're reading or editing higher up.
    val lastId = note.blocks.lastOrNull()?.id
    var seenLastId by remember { mutableStateOf(lastId) }
    LaunchedEffect(lastId) {
        if (lastId == seenLastId) return@LaunchedEffect
        seenLastId = lastId
        if (pendingFocus != null) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= info.totalItemsCount - 3) listState.animateScrollToItem(currentNote.blocks.size + 1)
    }
    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = if (compact) Dimens.SpaceS else Dimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            item(key = "title", contentType = "title") {
                EditorTextField(
                    value = note.title,
                    onValueChange = actions::onTitleChange,
                    hint = stringResource(R.string.title_hint),
                    style = if (compact) typography.titleMedium else typography.headlineLarge,
                    modifier = Modifier.padding(vertical = if (compact) Dimens.SpaceXs else Dimens.SpaceS),
                )
            }
            item(key = "category", contentType = "category") {
                Column {
                    CategoryPicker(note.category, actions::onCategoryChange, compact)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (note.blocks.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Text(
                        stringResource(R.string.empty_note_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (compact) Dimens.CompactTouchTarget else Dimens.TouchTarget)
                            .clickable { actions.onAddBlock(BlockType.Text) }
                            .padding(vertical = Dimens.SpaceM),
                    )
                }
            }
            items(note.blocks, key = { it.id }, contentType = { it::class }) { block ->
                BlockRow(
                    block = block,
                    requestFocus = pendingFocus == block.id,
                    actions = actions,
                    compact = compact,
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.fade(),
                        fadeOutSpec = null,
                    ),
                )
            }
        }
        // Grows and shrinks as statuses come and go, so the note above slides instead of jumping.
        Column(Modifier.fillMaxWidth().animateContentSize(Motion.move())) {
            AssistStatus(assist, actions, voiceActions::onOpenSettings, Modifier.padding(top = Dimens.SpaceS))
            VoiceStatus(voiceState, voiceActions, Modifier.padding(top = Dimens.SpaceS), compact)
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = Dimens.SpaceS),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        EditorToolbar(
            noteId = note.id,
            taskMode = note.taskMode,
            actions = actions,
            recording = recording,
            organising = assist == AssistState.Working,
            summarising = assist == AssistState.Summarising,
            onMic = {
                haptics.performHapticFeedback(if (recording) HapticFeedbackType.Confirm else HapticFeedbackType.ToggleOn)
                voiceActions.onMicToggle()
            },
            onShare = onShare,
            canUndo = canUndo,
            canRedo = canRedo,
            dense = compact,
        )
    }
}

/**
 * The note's category: one chip ("+ Category" when it has none) that opens the full row of
 * choices, so it takes a single line even in the floating window.
 */
@Composable
private fun CategoryPicker(category: NoteCategory?, onChange: (NoteCategory?) -> Unit, compact: Boolean) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    if (choosing) {
        CategoryChips(
            selected = category,
            onSelect = {
                onChange(it)
                choosing = false
            },
            noneLabel = stringResource(R.string.category_none),
            dense = compact,
        )
    } else {
        val label = category?.let { stringResource(it.labelRes) } ?: stringResource(R.string.category_add)
        Row(Modifier.padding(bottom = if (compact) Dimens.SpaceXs else Dimens.SpaceS)) {
            PillButton(
                text = label,
                onClick = { choosing = true },
                dense = compact,
            )
        }
    }
}

@Composable
private fun BlockRow(
    block: Block,
    requestFocus: Boolean,
    actions: NoteEditorActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            actions.onFocusConsumed()
        }
    }

    val baseStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val ticked = (block as? Block.Checklist)?.checked ?: (block as? Block.Task)?.done ?: false
    // Done tasks ease to secondary ink (about 5:1, not 50% alpha at about 3.5:1): done, but readable.
    val ink by animateColorAsState(
        if (block is Block.Task && ticked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        Motion.fade(Motion.Press),
        label = "ink",
    )
    val style = when (block) {
        is Block.Text -> baseStyle
        is Block.Checklist ->
            if (block.checked) baseStyle.copy(textDecoration = TextDecoration.LineThrough) else baseStyle
        is Block.Task -> baseStyle.copy(color = ink)
    }
    val haptics = LocalHapticFeedback.current
    val toggle = {
        haptics.performHapticFeedback(if (ticked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        actions.onToggle(block.id)
    }

    // TalkBack reads the checkbox with its item ("Task, buy milk"), not just "checkbox".
    val checkModifier = if (block is Block.Text) Modifier else {
        val kind = stringResource(if (block is Block.Checklist) R.string.checklist_item else R.string.task_item)
        val label = if (block.text.isBlank()) kind else stringResource(R.string.checkbox_label, kind, block.text)
        Modifier.semantics { contentDescription = label }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (block) {
            is Block.Text -> Unit
            is Block.Checklist ->
                Checkbox(block.checked, onCheckedChange = { toggle() }, modifier = checkModifier)
            is Block.Task ->
                Checkbox(block.done, onCheckedChange = { toggle() }, modifier = checkModifier)
        }
        EditorTextField(
            value = block.text,
            onValueChange = { actions.onTextChange(block.id, it) },
            hint = stringResource(R.string.block_hint),
            style = style,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Dimens.SpaceS)
                .focusRequester(focusRequester)
                // Backspace on an empty line removes it and moves up, as in Keep. Keyboards send a
                // Backspace key event when there's nothing left to delete.
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Backspace && block.text.isEmpty()) {
                        actions.onRemove(block.id)
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged {
                    focused = it.isFocused
                    actions.onFocusChanged(block.id, it.isFocused)
                },
        )
        if (focused) {
            val label = stringResource(R.string.remove_block)
            IconButton(
                onClick = { actions.onRemove(block.id) },
                modifier = Modifier.semantics { contentDescription = label },
            ) { Text("✕", modifier = Modifier.clearAndSetSemantics {}) }
        }
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val fallback = MaterialTheme.colorScheme.onSurface
    val color = style.color.takeOrElse { fallback }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style.copy(color = color),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.semantics { contentDescription = hint },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(hint, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

@Composable
private fun EditorToolbar(
    noteId: String,
    taskMode: TaskCompletionMode,
    actions: NoteEditorActions,
    recording: Boolean,
    organising: Boolean,
    summarising: Boolean,
    onMic: () -> Unit,
    onShare: (() -> Unit)?,
    canUndo: Boolean,
    canRedo: Boolean,
    dense: Boolean,
) {
    var showTools by rememberSaveable(noteId) { mutableStateOf(false) }
    val busy = organising || summarising
    val gap = if (dense) Dimens.SpaceXs else Dimens.SpaceS
    Column(
        Modifier.fillMaxWidth().padding(vertical = gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                text = stringResource(if (recording) R.string.voice_stop else R.string.voice_record),
                onClick = onMic,
                selected = true,
                dense = dense,
            )
            val toolsState = stringResource(if (showTools) R.string.tools_expanded else R.string.tools_collapsed)
            PillButton(
                text = stringResource(R.string.editor_tools),
                onClick = { showTools = !showTools },
                modifier = Modifier.semantics { stateDescription = toolsState },
                selected = showTools,
                dense = dense,
            )
            if (!dense) {
                PillButton(stringResource(R.string.edit_undo), actions::onUndo, enabled = canUndo)
                PillButton(stringResource(R.string.edit_redo), actions::onRedo, enabled = canRedo)
            }
        }
        AnimatedVisibility(
            visible = showTools,
            enter = fadeIn(Motion.fade()) + expandVertically(Motion.move(), expandFrom = Alignment.Top),
            exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.move(), shrinkTowards = Alignment.Top),
        ) {
            val tools: @Composable () -> Unit = {
                BlockType.entries.forEach { type ->
                    PillButton(
                        text = stringResource(
                            when (type) {
                                BlockType.Text -> R.string.block_text
                                BlockType.Checklist -> R.string.block_checklist
                                BlockType.Task -> R.string.block_task
                            },
                        ),
                        onClick = {
                            actions.onChangeType(type)
                            if (dense) showTools = false
                        },
                        dense = dense,
                    )
                }
                PillButton(
                    text = stringResource(if (organising) R.string.ai_organising else R.string.ai_organise),
                    onClick = actions::onOrganise,
                    dense = dense,
                    enabled = !busy,
                )
                PillButton(
                    text = stringResource(if (summarising) R.string.ai_summarising else R.string.ai_summarise),
                    onClick = actions::onSummarise,
                    dense = dense,
                    enabled = !busy,
                )
                if (dense) {
                    PillButton(stringResource(R.string.edit_undo), actions::onUndo, dense = true, enabled = canUndo)
                    PillButton(stringResource(R.string.edit_redo), actions::onRedo, dense = true, enabled = canRedo)
                }
                PillButton(
                    text = stringResource(
                        if (taskMode == TaskCompletionMode.MoveToBottom) R.string.mode_move_to_bottom
                        else R.string.mode_keep_in_place
                    ),
                    onClick = {
                        actions.onTaskModeChange(
                            if (taskMode == TaskCompletionMode.MoveToBottom) TaskCompletionMode.KeepInPlace
                            else TaskCompletionMode.MoveToBottom
                        )
                    },
                    selected = taskMode == TaskCompletionMode.MoveToBottom,
                    dense = dense,
                )
                if (onShare != null) PillButton(stringResource(R.string.share_note), onShare, dense = dense)
            }
            if (dense) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) { tools() }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) { tools() }
            }
        }
    }
}

/** The AI step's progress, result (with Undo) or error. Nothing when idle. */
@Composable
private fun AssistStatus(
    assist: AssistState,
    actions: NoteEditorActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (assist == AssistState.Idle) return
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(colors.surfaceVariant)
            .padding(horizontal = Dimens.SpaceS)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        val message = when (assist) {
            AssistState.Working -> stringResource(R.string.ai_organising)
            AssistState.Done -> stringResource(R.string.ai_organised)
            AssistState.Summarising -> stringResource(R.string.ai_summarising)
            AssistState.Summarised -> stringResource(R.string.ai_summarised)
            is AssistState.Error -> assist.message
            AssistState.Idle -> ""
        }
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically).padding(Dimens.SpaceS),
        )
        if (assist is AssistState.Error && assist.needsSettings) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
        }
        when (assist) {
            AssistState.Working, AssistState.Summarising ->
                TextButton(onClick = actions::onDismissAssist) { Text(stringResource(R.string.cancel)) }
            AssistState.Done, AssistState.Summarised -> {
                TextButton(onClick = actions::onUndoOrganise) { Text(stringResource(R.string.ai_undo)) }
                TextButton(onClick = actions::onDismissAssist) { Text(stringResource(R.string.ai_keep)) }
            }
            else -> TextButton(onClick = actions::onDismissAssist) { Text(stringResource(R.string.voice_dismiss)) }
        }
    }
}
