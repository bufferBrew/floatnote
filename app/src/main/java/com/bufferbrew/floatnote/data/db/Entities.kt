package com.bufferbrew.floatnote.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** [com.bufferbrew.floatnote.domain.model.TaskCompletionMode] name. */
    val taskMode: String,
    val updatedAt: Long,
    /** Pinned notes list first. Added in version 3. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** [com.bufferbrew.floatnote.domain.model.NoteCategory.key], or null. Added in version 4. */
    val category: String? = null,
)

@Entity(
    tableName = "blocks",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class BlockEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val position: Int,
    /** [com.bufferbrew.floatnote.domain.model.BlockType] name. */
    val type: String,
    val text: String,
    /** Checklist `checked` or task `done`; always false for text blocks. */
    val checked: Boolean,
)

/** Result row of [NoteDao.observeSummaries]. */
data class NoteSummaryRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val pinned: Boolean,
    val category: String?,
)

/**
 * One dictation as it went into a note, for the History screen. Not tied to the note by a
 * foreign key: history outlives deleted notes, like Wispr Flow's.
 */
@Entity(tableName = "dictations", indices = [Index("createdAt")])
data class DictationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: String,
    /** What was added to the note (after vocabulary and AI clean-up). */
    val text: String,
    /** What the recognizer heard, when it differs from [text]. */
    val raw: String?,
    val createdAt: Long,
)

/** A vocabulary word. With a [replacement], heard [term] is rewritten to it. */
@Entity(tableName = "vocabulary")
data class VocabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val term: String,
    val replacement: String,
)
