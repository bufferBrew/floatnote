package com.bufferbrew.floatnote.data

import com.bufferbrew.floatnote.data.db.DictationEntity
import com.bufferbrew.floatnote.data.db.VocabEntity
import com.bufferbrew.floatnote.data.db.VoiceDao
import com.bufferbrew.floatnote.domain.model.Dictation
import com.bufferbrew.floatnote.domain.model.VocabEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Dictation history and the vocabulary. */
interface VoiceRepository {
    /** Newest first. */
    fun observeHistory(): Flow<List<Dictation>>

    /** [audio]: the name of its kept recording, if any. */
    suspend fun addDictation(noteId: String, text: String, raw: String?, audio: String? = null)

    suspend fun deleteDictation(id: Long)

    suspend fun clearHistory()

    fun observeVocabulary(): Flow<List<VocabEntry>>

    suspend fun addVocab(term: String, replacement: String)

    suspend fun deleteVocab(id: Long)
}

class RoomVoiceRepository(
    private val dao: VoiceDao,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Kept recordings: deleted with their dictation, and trimmed as history is. */
    private val recordings: RecordingStore? = null,
) : VoiceRepository {

    override fun observeHistory(): Flow<List<Dictation>> =
        dao.observeDictations().map { rows ->
            rows.map { Dictation(it.id, it.noteId, it.text, it.raw, it.createdAt, it.audio) }
        }

    override suspend fun addDictation(noteId: String, text: String, raw: String?, audio: String?) {
        dao.addDictation(
            DictationEntity(noteId = noteId, text = text, raw = raw?.takeIf { it != text }, createdAt = clock(), audio = audio),
            keep = HISTORY_LIMIT,
        )
        // Recordings of entries trimmed off the end go too.
        recordings?.let { store ->
            val referenced = dao.audioNames().toSet()
            withContext(Dispatchers.IO) { store.prune(referenced) }
        }
    }

    override suspend fun deleteDictation(id: Long) {
        val audio = dao.audioOf(id)
        withContext(Dispatchers.IO) { recordings?.delete(audio) }
        dao.deleteDictation(id)
    }

    override suspend fun clearHistory() {
        dao.clearDictations()
        withContext(Dispatchers.IO) { recordings?.clear() }
    }

    override fun observeVocabulary(): Flow<List<VocabEntry>> =
        dao.observeVocabulary().map { rows -> rows.map { VocabEntry(it.id, it.term, it.replacement) } }

    override suspend fun addVocab(term: String, replacement: String) =
        dao.insertVocab(VocabEntity(term = term.trim(), replacement = replacement.trim()))

    override suspend fun deleteVocab(id: Long) = dao.deleteVocab(id)

    private companion object {
        const val HISTORY_LIMIT = 500
    }
}
