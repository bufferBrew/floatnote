package com.bufferbrew.floatnote.data

import com.bufferbrew.floatnote.data.db.DictationEntity
import com.bufferbrew.floatnote.data.db.VocabEntity
import com.bufferbrew.floatnote.data.db.VoiceDao
import com.bufferbrew.floatnote.domain.model.Dictation
import com.bufferbrew.floatnote.domain.model.VocabEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Dictation history and the vocabulary. */
interface VoiceRepository {
    /** Newest first. */
    fun observeHistory(): Flow<List<Dictation>>

    suspend fun addDictation(noteId: String, text: String, raw: String?)

    suspend fun deleteDictation(id: Long)

    suspend fun clearHistory()

    fun observeVocabulary(): Flow<List<VocabEntry>>

    suspend fun addVocab(term: String, replacement: String)

    suspend fun deleteVocab(id: Long)
}

class RoomVoiceRepository(
    private val dao: VoiceDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceRepository {

    override fun observeHistory(): Flow<List<Dictation>> =
        dao.observeDictations().map { rows ->
            rows.map { Dictation(it.id, it.noteId, it.text, it.raw, it.createdAt) }
        }

    override suspend fun addDictation(noteId: String, text: String, raw: String?) =
        dao.addDictation(
            DictationEntity(noteId = noteId, text = text, raw = raw?.takeIf { it != text }, createdAt = clock()),
            keep = HISTORY_LIMIT,
        )

    override suspend fun deleteDictation(id: Long) = dao.deleteDictation(id)

    override suspend fun clearHistory() = dao.clearDictations()

    override fun observeVocabulary(): Flow<List<VocabEntry>> =
        dao.observeVocabulary().map { rows -> rows.map { VocabEntry(it.id, it.term, it.replacement) } }

    override suspend fun addVocab(term: String, replacement: String) =
        dao.insertVocab(VocabEntity(term = term.trim(), replacement = replacement.trim()))

    override suspend fun deleteVocab(id: Long) = dao.deleteVocab(id)

    private companion object {
        const val HISTORY_LIMIT = 500
    }
}
