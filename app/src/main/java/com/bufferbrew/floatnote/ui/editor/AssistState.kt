package com.bufferbrew.floatnote.ui.editor

/** Where the open note's "Organise" (AI) step is. */
sealed interface AssistState {
    data object Idle : AssistState

    data object Working : AssistState

    /** Organised; the previous version can be restored with Undo until the note closes. */
    data object Done : AssistState

    data class Error(val message: String, val needsSettings: Boolean = false) : AssistState
}
