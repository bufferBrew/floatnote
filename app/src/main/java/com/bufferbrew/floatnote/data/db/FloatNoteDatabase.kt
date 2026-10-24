package com.bufferbrew.floatnote.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 added dictation history and the vocabulary (new tables only, so Room's generated
 * auto-migration keeps every existing note). Never fall back to a destructive migration.
 */
@Database(
    entities = [NoteEntity::class, BlockEntity::class, DictationEntity::class, VocabEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class FloatNoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun voiceDao(): VoiceDao

    companion object {
        fun create(context: Context): FloatNoteDatabase =
            Room.databaseBuilder(context, FloatNoteDatabase::class.java, "floatnote.db").build()
    }
}
