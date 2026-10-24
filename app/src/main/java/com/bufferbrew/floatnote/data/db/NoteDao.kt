package com.bufferbrew.floatnote.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): NoteEntity?

    @Query("SELECT * FROM blocks WHERE noteId = :noteId ORDER BY position")
    suspend fun blocks(noteId: String): List<BlockEntity>

    @Upsert
    suspend fun upsertNote(note: NoteEntity)

    @Upsert
    suspend fun upsertBlocks(blocks: List<BlockEntity>)

    @Query("DELETE FROM blocks WHERE noteId = :noteId")
    suspend fun deleteBlocks(noteId: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    /** Notes are small, so a save rewrites every block rather than diffing. */
    @Transaction
    suspend fun replace(note: NoteEntity, blocks: List<BlockEntity>) {
        upsertNote(note)
        deleteBlocks(note.id)
        upsertBlocks(blocks)
    }
}
