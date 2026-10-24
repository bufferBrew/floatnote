package com.bufferbrew.floatnote.data

import android.content.Context
import android.net.Uri
import com.bufferbrew.floatnote.FloatNoteApp
import com.bufferbrew.floatnote.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bufferbrew.floatnote.domain.NotesBackup as Format

/** Reads and writes backup files the user picked (Storage Access Framework). */
object NotesBackup {

    /** Returns the message to show. */
    suspend fun export(context: Context, app: FloatNoteApp, uri: Uri): String {
        app.session.flush()
        app.session.awaitSaves()
        val notes = app.repository.all()
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("Couldn't open the file")
            stream.bufferedWriter().use { it.write(Format.toJson(notes)) }
        }
        return context.resources.getQuantityString(R.plurals.backup_exported, notes.size, notes.size)
    }

    suspend fun import(context: Context, app: FloatNoteApp, uri: Uri): String {
        val text = withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Couldn't open the file")
            // A backup of thousands of notes is a few MB; refuse anything far bigger.
            stream.use { input ->
                val bytes = input.readNBytesCompat(MAX_BYTES + 1)
                require(bytes.size <= MAX_BYTES) { "The file is too big to be a FloatNote backup." }
                String(bytes, Charsets.UTF_8)
            }
        }
        val notes = Format.fromJson(text)
        notes.forEach { app.repository.save(it) }
        return context.resources.getQuantityString(R.plurals.backup_imported, notes.size, notes.size)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private const val MAX_BYTES = 50 * 1024 * 1024
}
