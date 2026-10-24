package com.bufferbrew.floatnote.ui.editor

import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode

/** Everything the editor UI can ask for. [NoteViewModel] is the real implementation. */
interface NoteEditorActions {
    fun onTitleChange(title: String)
    fun onToggle(blockId: String)
    fun onTextChange(blockId: String, text: String)
    fun onAddBlock(type: BlockType)
    fun onRemove(blockId: String)
    fun onChangeType(type: BlockType)
    fun onTaskModeChange(mode: TaskCompletionMode)
    fun onFocusChanged(blockId: String, focused: Boolean)
    fun onFocusConsumed()

    /** AI: restructure the open note. */
    fun onOrganise()
    fun onUndoOrganise()
    fun onDismissAssist()
}
