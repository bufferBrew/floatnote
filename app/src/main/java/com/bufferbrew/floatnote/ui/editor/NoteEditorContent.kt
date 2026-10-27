package com.bufferbrew.floatnote.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.ai.labelRes
import com.bufferbrew.floatnote.ui.components.CategoryChips
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.voice.VoiceActions
import com.bufferbrew.floatnote.voice.VoiceState
import com.bufferbrew.floatnote.voice.VoiceStatus

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
    val recording by remember { derivedStateOf { voiceState() is VoiceState.Recording } }
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
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= info.totalItemsCount - 3) listState.animateScrollToItem(currentNote.blocks.size + 1)
    }
    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            item(key = "title") {
                EditorTextField(
                    value = note.title,
                    onValueChange = actions::onTitleChange,
                    hint = stringResource(R.string.title_hint),
                    style = if (compact) typography.titleMedium else typography.titleLarge,
                    modifier = Modifier.padding(vertical = if (compact) Dimens.SpaceXs else Dimens.SpaceS),
                )
            }
            item(key = "category") {
                CategoryPicker(note.category, actions::onCategoryChange, compact)
            }
            if (note.blocks.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.empty_note_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.onAddBlock(BlockType.Text) }
                            .padding(vertical = Dimens.SpaceM),
                    )
                }
            }
            items(note.blocks, key = { it.id }) { block ->
                BlockRow(
                    block = block,
                    requestFocus = pendingFocus == block.id,
                    actions = actions,
                    compact = compact,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        AssistStatus(assist, actions, voiceActions::onOpenSettings, Modifier.padding(top = Dimens.SpaceS))
        VoiceStatus(voiceState, voiceActions, Modifier.padding(top = Dimens.SpaceS))
        EditorToolbar(
            taskMode = note.taskMode,
            actions = actions,
            recording = recording,
            organising = assist == AssistState.Working,
            onMic = voiceActions::onMicToggle,
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
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        CategoryChips(
            selected = category,
            onSelect = {
                onChange(it)
                choosing = false
            },
            noneLabel = stringResource(R.string.category_none),
            dense = true,
        )
    } else {
        val label = category?.let { stringResource(it.labelRes) } ?: stringResource(R.string.category_add)
        val choose = stringResource(R.string.category_choose)
        Row(Modifier.padding(bottom = if (compact) Dimens.SpaceXs else Dimens.SpaceS)) {
            PillButton(
                text = label,
                onClick = { choosing = true },
                dense = true,
                modifier = Modifier.semantics { onClick(label = choose) { choosing = true; true } },
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
    val style = when (block) {
        is Block.Text -> baseStyle
        is Block.Checklist ->
            if (block.checked) baseStyle.copy(textDecoration = TextDecoration.LineThrough) else baseStyle
        is Block.Task ->
            // Secondary ink (about 5:1), not 50% alpha (about 3.5:1): done, but still readable.
            if (block.done) baseStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            else baseStyle
    }

    // TalkBack reads the checkbox with its item ("Task, buy milk"), not just "checkbox".
    val checkLabel = when (block) {
        is Block.Text -> ""
        is Block.Checklist -> stringResource(R.string.checklist_item)
        is Block.Task -> stringResource(R.string.task_item)
    }.let { kind -> if (block.text.isBlank()) kind else stringResource(R.string.checkbox_label, kind, block.text) }
    val checkModifier = Modifier.semantics { contentDescription = checkLabel }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (block) {
            is Block.Text -> Unit
            is Block.Checklist ->
                Checkbox(block.checked, onCheckedChange = { actions.onToggle(block.id) }, modifier = checkModifier)
            is Block.Task ->
                Checkbox(block.done, onCheckedChange = { actions.onToggle(block.id) }, modifier = checkModifier)
        }
        EditorTextField(
            value = block.text,
            onValueChange = { actions.onTextChange(block.id, it) },
            hint = stringResource(R.string.block_hint),
            style = style,
            modifier = Modifier
                .weight(1f)
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
        modifier = modifier,
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
    taskMode: TaskCompletionMode,
    actions: NoteEditorActions,
    recording: Boolean,
    organising: Boolean,
    onMic: () -> Unit,
    onShare: (() -> Unit)?,
    canUndo: Boolean,
    canRedo: Boolean,
    dense: Boolean,
) {
    // Scrolls sideways so it fits a narrow floating window.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = if (dense) Dimens.SpaceXs else Dimens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(if (dense) Dimens.SpaceXs else Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(
            text = stringResource(if (recording) R.string.voice_stop else R.string.voice_record),
            onClick = onMic,
            selected = recording,
            dense = dense,
        )
        // Next to Record, so it's visible without scrolling the toolbar in the floating window.
        PillButton(
            text = stringResource(if (organising) R.string.ai_organising else R.string.ai_organise),
            onClick = actions::onOrganise,
            dense = dense,
        )
        if (canUndo) PillButton(stringResource(R.string.edit_undo), actions::onUndo, dense = dense)
        if (canRedo) PillButton(stringResource(R.string.edit_redo), actions::onRedo, dense = dense)
        PillButton(stringResource(R.string.block_text), { actions.onChangeType(BlockType.Text) }, dense = dense)
        PillButton(stringResource(R.string.block_checklist), { actions.onChangeType(BlockType.Checklist) }, dense = dense)
        PillButton(stringResource(R.string.block_task), { actions.onChangeType(BlockType.Task) }, dense = dense)
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
}

/** The Organise step's progress, result (with Undo) or error. Nothing when idle. */
@Composable
private fun AssistStatus(
    assist: AssistState,
    actions: NoteEditorActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (assist == AssistState.Idle) return
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(colors.surfaceVariant)
            .padding(start = Dimens.SpaceM, end = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val message = when (assist) {
            AssistState.Working -> stringResource(R.string.ai_organising)
            AssistState.Done -> stringResource(R.string.ai_organised)
            is AssistState.Error -> assist.message
            AssistState.Idle -> ""
        }
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = Dimens.SpaceS),
        )
        if (assist is AssistState.Error && assist.needsSettings) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
        }
        when (assist) {
            AssistState.Working ->
                TextButton(onClick = actions::onDismissAssist) { Text(stringResource(R.string.cancel)) }
            AssistState.Done -> {
                TextButton(onClick = actions::onUndoOrganise) { Text(stringResource(R.string.ai_undo)) }
                TextButton(onClick = actions::onDismissAssist) { Text(stringResource(R.string.ai_keep)) }
            }
            else -> TextButton(onClick = actions::onDismissAssist) { Text(stringResource(R.string.voice_dismiss)) }
        }
    }
}
