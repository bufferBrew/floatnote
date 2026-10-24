package com.bufferbrew.floatnote.ui.editor

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import com.bufferbrew.floatnote.ui.theme.Dimens

/** Alpha for completed tasks, which stay readable but recede. */
private const val DoneAlpha = 0.5f

/**
 * The note editor. Stateless and host-agnostic: the Activity and the floating overlay both
 * render exactly this, so every feature works in both (CLAUDE.md requirement 3).
 */
@Composable
fun NoteEditorContent(
    note: Note,
    pendingFocus: String?,
    actions: NoteEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            item(key = "title") {
                EditorTextField(
                    value = note.title,
                    onValueChange = actions::onTitleChange,
                    hint = stringResource(R.string.title_hint),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = Dimens.SpaceS),
                )
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
                    modifier = Modifier.animateItem(),
                )
            }
        }
        HorizontalDivider()
        EditorToolbar(taskMode = note.taskMode, actions = actions)
    }
}

@Composable
private fun BlockRow(
    block: Block,
    requestFocus: Boolean,
    actions: NoteEditorActions,
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

    val baseStyle = MaterialTheme.typography.bodyLarge
    val style = when (block) {
        is Block.Text -> baseStyle
        is Block.Checklist ->
            if (block.checked) baseStyle.copy(textDecoration = TextDecoration.LineThrough) else baseStyle
        is Block.Task ->
            if (block.done) baseStyle.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = DoneAlpha))
            else baseStyle
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (block) {
            is Block.Text -> Unit
            is Block.Checklist -> Checkbox(block.checked, onCheckedChange = { actions.onToggle(block.id) })
            is Block.Task -> Checkbox(block.done, onCheckedChange = { actions.onToggle(block.id) })
        }
        EditorTextField(
            value = block.text,
            onValueChange = { actions.onTextChange(block.id, it) },
            hint = stringResource(R.string.block_hint),
            style = style,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
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
            ) { Text("✕") }
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
private fun EditorToolbar(taskMode: TaskCompletionMode, actions: NoteEditorActions) {
    // Scrolls sideways so it fits a narrow floating window.
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { actions.onChangeType(BlockType.Text) }) {
            Text(stringResource(R.string.block_text))
        }
        TextButton(onClick = { actions.onChangeType(BlockType.Checklist) }) {
            Text(stringResource(R.string.block_checklist))
        }
        TextButton(onClick = { actions.onChangeType(BlockType.Task) }) {
            Text(stringResource(R.string.block_task))
        }
        TextButton(
            onClick = {
                actions.onTaskModeChange(
                    if (taskMode == TaskCompletionMode.MoveToBottom) TaskCompletionMode.KeepInPlace
                    else TaskCompletionMode.MoveToBottom
                )
            },
        ) {
            Text(
                stringResource(
                    if (taskMode == TaskCompletionMode.MoveToBottom) R.string.mode_move_to_bottom
                    else R.string.mode_keep_in_place
                )
            )
        }
    }
}
