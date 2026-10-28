package com.bufferbrew.floatnote.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.bufferbrew.floatnote.domain.NoteMarkdown
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.isBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Keeps every note as a `.md` file in a folder the user picked (Storage Access Framework, so
 * Drive, a local Obsidian vault or a USB stick all work). Each note's file is remembered, so a
 * renamed note renames its file and a deleted note deletes it. Calls do blocking I/O: run them
 * off the main thread.
 */
class MarkdownExport(private val context: Context) {
    private val prefs = context.getSharedPreferences("markdown_export", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    /** The chosen folder (a document tree), or null when exporting is off. */
    val folder: Uri? get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** The folder's name, for Settings. */
    fun folderName(): String? {
        val tree = folder ?: return null
        return runCatching {
            resolver.query(folderDocument(tree), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: tree.lastPathSegment
    }

    /** Starts exporting to [tree] (null stops), keeping access to it across restarts. */
    fun setFolder(tree: Uri?) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        folder?.takeIf { it != tree }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, flags) } }
        // Files in the old folder stay there; forget them so a new folder gets its own.
        val editor = prefs.edit().clear()
        if (tree != null) {
            resolver.takePersistableUriPermission(tree, flags)
            editor.putString(KEY_FOLDER, tree.toString())
        }
        editor.apply()
    }

    /** Writes [note]'s file, creating or renaming it as needed. A blank note has none. */
    fun export(note: Note) {
        val tree = folder ?: return
        if (note.isBlank) {
            remove(note.id)
            return
        }
        val name = NoteMarkdown.fileName(note)
        val text = NoteMarkdown.render(note)
        var doc = prefs.getString(docKey(note.id), null)?.let(Uri::parse)
        if (doc != null && prefs.getString(nameKey(note.id), null) != name) {
            // The rename can give the file a new URI; if the provider can't rename, keep the old name.
            doc = runCatching { DocumentsContract.renameDocument(resolver, doc, name) }.getOrNull() ?: doc
        }
        if (doc == null || !write(doc, text)) {
            // Never written, or deleted from the folder since: make it again.
            doc = runCatching {
                DocumentsContract.createDocument(resolver, folderDocument(tree), MIME, name)
            }.getOrNull() ?: return
            if (!write(doc, text)) return
        }
        prefs.edit().putString(docKey(note.id), doc.toString()).putString(nameKey(note.id), name).apply()
    }

    /** Deletes the file of a note that was deleted. */
    fun remove(noteId: String) {
        val doc = prefs.getString(docKey(noteId), null)?.let(Uri::parse) ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        prefs.edit().remove(docKey(noteId)).remove(nameKey(noteId)).apply()
    }

    private fun write(doc: Uri, text: String): Boolean = runCatching {
        resolver.openOutputStream(doc, "wt")?.use { it.write(text.toByteArray()) } != null
    }.getOrDefault(false)

    private fun folderDocument(tree: Uri) =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private companion object {
        const val KEY_FOLDER = "folder"
        const val MIME = "text/markdown"
        fun docKey(id: String) = "doc_$id"
        fun nameKey(id: String) = "name_$id"
    }
}

/**
 * Saves go to [inner] as usual; then, when a Markdown folder is set, the note's file is updated
 * a few seconds later. Typing saves often, so one file write covers a burst of saves.
 */
class ExportingNoteRepository(
    private val inner: NoteRepository,
    private val export: MarkdownExport,
    private val scope: CoroutineScope,
    private val delayMs: Long = 3_000,
) : NoteRepository by inner {
    private val pending = mutableMapOf<String, Job>()

    override suspend fun save(note: Note) {
        inner.save(note)
        schedule(note.id) { export.export(it) }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        inner.setPinned(id, pinned)
        schedule(id) { export.export(it) }
    }

    override suspend fun delete(id: String) {
        inner.delete(id)
        synchronized(pending) { pending.remove(id)?.cancel() }
        if (export.folder != null) withContext(Dispatchers.IO) { runCatching { export.remove(id) } }
    }

    /** Writes every note now, after a folder is chosen. */
    suspend fun exportAll() {
        if (export.folder == null) return
        val notes = inner.all()
        withContext(Dispatchers.IO) { notes.forEach { runCatching { export.export(it) } } }
    }

    private fun schedule(id: String, write: (Note) -> Unit) {
        if (export.folder == null) return
        synchronized(pending) {
            pending.remove(id)?.cancel()
            pending[id] = scope.launch(Dispatchers.IO) {
                delay(delayMs)
                // The latest saved version, not the one that triggered this.
                inner.load(id)?.let { runCatching { write(it) } }
                val self = coroutineContext[Job]
                synchronized(pending) { if (pending[id] === self) pending.remove(id) }
            }
        }
    }
}
