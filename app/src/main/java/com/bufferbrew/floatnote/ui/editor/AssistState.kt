package com.bufferbrew.floatnote.ui.editor

/** Where the open note's AI step (✦ Organise or ✦ Summarise) is. */
sealed interface AssistState {
    data object Idle : AssistState

    data object Working : AssistState

    /** Organised; the previous version can be restored with Undo until the note closes. */
    data object Done : AssistState

    data object Summarising : AssistState

    /** A summary and action items were added; Undo works as after Organise. */
    data object Summarised : AssistState

    data class Error(val message: String, val needsSettings: Boolean = false) : AssistState
}
