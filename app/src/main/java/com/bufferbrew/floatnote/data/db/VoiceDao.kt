package com.bufferbrew.floatnote.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceDao {

    @Query("SELECT * FROM dictations ORDER BY createdAt DESC, id DESC")
    fun observeDictations(): Flow<List<DictationEntity>>

    @Insert
    suspend fun insertDictation(dictation: DictationEntity)

    @Query("DELETE FROM dictations WHERE id NOT IN (SELECT id FROM dictations ORDER BY createdAt DESC, id DESC LIMIT :keep)")
    suspend fun trimDictations(keep: Int)

    /** Adds one and drops the oldest beyond [keep], so history can't grow without bound. */
    @Transaction
    suspend fun addDictation(dictation: DictationEntity, keep: Int) {
        insertDictation(dictation)
        trimDictations(keep)
    }

    @Query("SELECT audio FROM dictations WHERE audio IS NOT NULL")
    suspend fun audioNames(): List<String>

    @Query("SELECT audio FROM dictations WHERE id = :id")
    suspend fun audioOf(id: Long): String?

    @Query("DELETE FROM dictations WHERE id = :id")
    suspend fun deleteDictation(id: Long)

    @Query("DELETE FROM dictations")
    suspend fun clearDictations()

    @Query("SELECT * FROM vocabulary ORDER BY term COLLATE NOCASE")
    fun observeVocabulary(): Flow<List<VocabEntity>>

    @Insert
    suspend fun insertVocab(entry: VocabEntity)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun deleteVocab(id: Long)
}
