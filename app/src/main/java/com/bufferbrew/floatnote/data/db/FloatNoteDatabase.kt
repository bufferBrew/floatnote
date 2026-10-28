package com.bufferbrew.floatnote.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 added dictation history and the vocabulary (new tables); version 3 added
 * `notes.pinned` (default 0); version 4 added `notes.category` (null); version 5 added
 * `dictations.audio` (null). All are Room auto-migrations that keep every existing note, and
 * `DatabaseTest` checks each step. Never fall back to a destructive migration.
 */
@Database(
    entities = [NoteEntity::class, BlockEntity::class, DictationEntity::class, VocabEntity::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class FloatNoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun voiceDao(): VoiceDao

    companion object {
        fun create(context: Context): FloatNoteDatabase =
            Room.databaseBuilder(context, FloatNoteDatabase::class.java, "floatnote.db").build()
    }
}
