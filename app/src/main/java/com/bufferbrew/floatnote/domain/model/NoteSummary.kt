package com.bufferbrew.floatnote.domain.model

/** A row in the note list: enough to recognise a note without loading its blocks. */
data class NoteSummary(
    val id: String,
    val title: String,
    /** First non-blank line of the note, or empty. */
    val preview: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val category: NoteCategory? = null,
)

/** True when there is nothing worth keeping: no title and every line blank. */
val Note.isBlank: Boolean
    get() = title.isBlank() && blocks.all { it.text.isBlank() }
