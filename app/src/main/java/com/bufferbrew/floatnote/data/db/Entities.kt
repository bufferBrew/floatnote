package com.bufferbrew.floatnote.data.db

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
)
