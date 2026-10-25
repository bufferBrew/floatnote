package com.bufferbrew.floatnote.data

import com.bufferbrew.floatnote.data.db.NoteDao
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.domain.model.NoteSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface NoteRepository {
    /**
     * Pinned first, then most recently edited. A non-blank [query] searches titles and lines; a
     * [category] keeps only notes in it.
     */
    fun observeSummaries(query: String = "", category: NoteCategory? = null): Flow<List<NoteSummary>>

    /** Titles of up to [limit] other notes in [category], newest first. */
    suspend fun titlesIn(category: NoteCategory, excludeId: String, limit: Int): List<String>

    /** Pins without touching "last edited". */
    suspend fun setPinned(id: String, pinned: Boolean)

    /** Every note, for export. */
    suspend fun all(): List<Note>

    suspend fun load(id: String): Note?

    /** The last-edited note, or null if there are none. */
    suspend fun mostRecent(): Note?

    suspend fun save(note: Note)

    suspend fun delete(id: String)
}

class RoomNoteRepository(
    private val dao: NoteDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : NoteRepository {

    override fun observeSummaries(query: String, category: NoteCategory?): Flow<List<NoteSummary>> =
        dao.observeSummaries(likePattern(query), category?.key).map { rows ->
            rows.map {
                NoteSummary(it.id, it.title, it.preview, it.updatedAt, it.pinned, NoteCategory.fromKey(it.category))
            }
        }

    override suspend fun titlesIn(category: NoteCategory, excludeId: String, limit: Int): List<String> =
        dao.titlesIn(category.key, excludeId, limit)

    override suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    override suspend fun all(): List<Note> = dao.everything().map { (note, blocks) -> note.toDomain(blocks) }

    /** "50%_off" must match literally, so LIKE's wildcards in the query are escaped. */
    private fun likePattern(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return ""
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    override suspend fun load(id: String): Note? =
        dao.noteWithBlocks(id)?.let { (note, blocks) -> note.toDomain(blocks) }

    override suspend fun mostRecent(): Note? =
        dao.mostRecentWithBlocks()?.let { (note, blocks) -> note.toDomain(blocks) }

    override suspend fun save(note: Note) =
        dao.replace(note.toEntity(updatedAt = clock()), note.toBlockEntities())

    override suspend fun delete(id: String) = dao.deleteNote(id)
}
