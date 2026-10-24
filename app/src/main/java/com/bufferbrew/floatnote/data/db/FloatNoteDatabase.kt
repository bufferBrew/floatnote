package com.bufferbrew.floatnote.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NoteEntity::class, BlockEntity::class], version = 1)
abstract class FloatNoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        fun create(context: Context): FloatNoteDatabase =
            Room.databaseBuilder(context, FloatNoteDatabase::class.java, "floatnote.db").build()
    }
}
