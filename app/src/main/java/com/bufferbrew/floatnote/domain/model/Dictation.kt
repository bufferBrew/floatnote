package com.bufferbrew.floatnote.domain.model

/** One dictation that went into a note. [raw] is what was heard, if clean-up changed it. */
data class Dictation(
    val id: Long,
    val noteId: String,
    val text: String,
    val raw: String?,
    val createdAt: Long,
)

/**
 * A vocabulary word, like Wispr Flow's dictionary. A bare [term] teaches the recognizer the
 * word and fixes its spelling; with a [replacement], hearing [term] writes the replacement.
 */
data class VocabEntry(
    val id: Long,
    val term: String,
    val replacement: String = "",
)
