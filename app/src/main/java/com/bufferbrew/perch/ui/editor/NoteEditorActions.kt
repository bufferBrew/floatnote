package com.bufferbrew.perch.ui.editor

import com.bufferbrew.perch.domain.model.BlockType
import com.bufferbrew.perch.domain.model.NoteCategory
import com.bufferbrew.perch.domain.model.TaskCompletionMode

/** Everything the editor UI can ask for. [NoteViewModel] is the real implementation. */
interface NoteEditorActions {
    fun onTitleChange(title: String)
    fun onToggle(blockId: String)
    fun onTextChange(blockId: String, text: String)
    fun onAddBlock(type: BlockType)
    fun onRemove(blockId: String)
    fun onChangeType(type: BlockType)
    fun onTaskModeChange(mode: TaskCompletionMode)

    /** Null clears it. */
    fun onCategoryChange(category: NoteCategory?)
    fun onFocusChanged(blockId: String, focused: Boolean)
    fun onFocusConsumed()

    fun onUndo()
    fun onRedo()

    /** AI: restructure the open note. */
    fun onOrganise()

    /** AI: add a summary line and the open action items as tasks. */
    fun onSummarise()
    fun onUndoOrganise()
    fun onDismissAssist()
}
