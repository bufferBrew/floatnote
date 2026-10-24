package com.bufferbrew.floatnote.data

import com.bufferbrew.floatnote.data.db.NoteDao
import com.bufferbrew.floatnote.domain.model.Note
import java.util.UUID

interface NoteRepository {
    suspend fun load(id: String): Note?

    /** The last-edited note, or a new empty one (saved) if there are none. */
    suspend fun mostRecentOrNew(): Note

    suspend fun save(note: Note)

    suspend fun delete(id: String)
}

class RoomNoteRepository(
    private val dao: NoteDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : NoteRepository {

    override suspend fun load(id: String): Note? =
        dao.note(id)?.toDomain(dao.blocks(id))

    override suspend fun mostRecentOrNew(): Note {
        dao.mostRecent()?.let { return it.toDomain(dao.blocks(it.id)) }
        return Note(id = UUID.randomUUID().toString()).also { save(it) }
    }

    override suspend fun save(note: Note) =
        dao.replace(note.toEntity(updatedAt = clock()), note.toBlockEntities())

    override suspend fun delete(id: String) = dao.deleteNote(id)
}
