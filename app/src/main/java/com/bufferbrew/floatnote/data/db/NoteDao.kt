package com.bufferbrew.floatnote.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    /**
     * Pinned first, then most recently edited. A non-empty [pattern] (a LIKE pattern, already
     * escaped with \\) keeps notes whose title or any line matches. Room re-emits when either
     * table changes, so previews stay current.
     */
    @Query(
        """
        SELECT n.id, n.title, n.updatedAt, n.pinned,
            COALESCE((SELECT b.text FROM blocks b WHERE b.noteId = n.id AND TRIM(b.text) != ''
                ORDER BY b.position LIMIT 1), '') AS preview
        FROM notes n
        WHERE :pattern = '' OR n.title LIKE :pattern ESCAPE '\' OR EXISTS (
            SELECT 1 FROM blocks b WHERE b.noteId = n.id AND b.text LIKE :pattern ESCAPE '\')
        ORDER BY n.pinned DESC, n.updatedAt DESC
        """
    )
    fun observeSummaries(pattern: String): Flow<List<NoteSummaryRow>>

    @Query("UPDATE notes SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun allNotes(): List<NoteEntity>

    /** Every note with its blocks, for export. */
    @Transaction
    suspend fun everything(): List<Pair<NoteEntity, List<BlockEntity>>> =
        allNotes().map { it to blocks(it.id) }

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): NoteEntity?

    @Query("SELECT * FROM blocks WHERE noteId = :noteId ORDER BY position")
    suspend fun blocks(noteId: String): List<BlockEntity>

    /** Note and blocks read together, so a save can't land between the two queries. */
    @Transaction
    suspend fun noteWithBlocks(id: String): Pair<NoteEntity, List<BlockEntity>>? {
        val note = note(id) ?: return null
        return note to blocks(id)
    }

    @Transaction
    suspend fun mostRecentWithBlocks(): Pair<NoteEntity, List<BlockEntity>>? {
        val note = mostRecent() ?: return null
        return note to blocks(note.id)
    }

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
