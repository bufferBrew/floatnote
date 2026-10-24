package com.bufferbrew.floatnote.data

import com.bufferbrew.floatnote.data.db.NoteDao
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface NoteRepository {
    /** All notes, most recently edited first. */
    fun observeSummaries(): Flow<List<NoteSummary>>

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

    override fun observeSummaries(): Flow<List<NoteSummary>> =
        dao.observeSummaries().map { rows ->
            rows.map { NoteSummary(it.id, it.title, it.preview, it.updatedAt) }
        }

    override suspend fun load(id: String): Note? =
        dao.note(id)?.toDomain(dao.blocks(id))

    override suspend fun mostRecent(): Note? =
        dao.mostRecent()?.let { it.toDomain(dao.blocks(it.id)) }

    override suspend fun save(note: Note) =
        dao.replace(note.toEntity(updatedAt = clock()), note.toBlockEntities())

    override suspend fun delete(id: String) = dao.deleteNote(id)
}
