package com.bufferbrew.floatnote.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bufferbrew.floatnote.data.NoteRepository
import com.bufferbrew.floatnote.domain.NoteEditor
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteSummary
import com.bufferbrew.floatnote.domain.model.isBlank
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The note-editing session: the note list plus at most one open note. A single instance lives in
 * [com.bufferbrew.floatnote.FloatNoteApp] and is shared by the full-screen app and the floating
 * overlay, so both always show the same note and edits in one appear in the other at once.
 *
 * State is Compose snapshot state (not a Flow) so text fields read it synchronously; going
 * through a collected Flow makes the cursor jump while typing. Edits are saved after
 * [saveDelayMs] of quiet. [persistScope] must outlive this ViewModel so the final save in
 * [onCleared] still runs.
 */
class NoteViewModel(
    private val repository: NoteRepository,
    private val persistScope: CoroutineScope,
    private val editor: NoteEditor = NoteEditor(),
    private val saveDelayMs: Long = 400,
) : ViewModel(), NoteEditorActions {

    /** All notes for the list screen. */
    val notes: Flow<List<NoteSummary>> = repository.observeSummaries()

    /** The open note, or null when the list should show. */
    var note by mutableStateOf<Note?>(null)
        private set

    /** Block the UI should move focus to once, then report back via [onFocusConsumed]. */
    var pendingFocus by mutableStateOf<String?>(null)
        private set

    /** Block that currently has focus, used as the anchor for new blocks. */
    private var focusedBlockId: String? = null
    private var saveJob: Job? = null
    private var dirty = false
    private val saveMutex = Mutex()

    fun open(noteId: String) {
        if (note?.id == noteId) return
        closeNote()
        viewModelScope.launch { note = repository.load(noteId) }
    }

    fun createNote() {
        closeNote()
        val created = editor.newNote()
        note = created
        pendingFocus = created.blocks.first().id
    }

    /** Opens the last-edited note, or a new one, unless a note is already open. */
    fun ensureOpen() {
        if (note != null) return
        viewModelScope.launch {
            if (note == null) note = repository.mostRecent() ?: editor.newNote()
        }
    }

    /** Saves the open note and returns to the list. A blank note is deleted instead. */
    fun closeNote() {
        val current = note ?: return
        if (current.isBlank) {
            saveJob?.cancel()
            dirty = false
            persist { repository.delete(current.id) }
        } else {
            flush()
        }
        note = null
        focusedBlockId = null
        pendingFocus = null
    }

    fun deleteNote(noteId: String) {
        if (note?.id == noteId) {
            saveJob?.cancel()
            dirty = false
            note = null
        }
        persist { repository.delete(noteId) }
    }

    override fun onTitleChange(title: String) = edit { editor.setTitle(it, title) }

    override fun onToggle(blockId: String) = edit { editor.toggle(it, blockId) }

    /** A newline typed into a list item splits it into two items, like Enter in Keep. */
    override fun onTextChange(blockId: String, text: String) = edit { current ->
        val block = current.blocks.firstOrNull { it.id == blockId }
        val newline = text.indexOf('\n')
        if (block == null || block is Block.Text || newline < 0) {
            editor.updateText(current, blockId, text)
        } else {
            val after = text.substring(newline + 1).replace("\n", "")
            val (split, newId) = editor.split(current, blockId, text.substring(0, newline), after)
            pendingFocus = newId
            split
        }
    }

    override fun onAddBlock(type: BlockType) = edit { current ->
        val (inserted, newId) = editor.insertAfter(current, focusedBlockId, type)
        pendingFocus = newId
        inserted
    }

    /** Removes the block and moves focus to the one above it, if any. */
    override fun onRemove(blockId: String) = edit { current ->
        val index = current.blocks.indexOfFirst { it.id == blockId }
        pendingFocus = current.blocks.getOrNull(index - 1)?.id
        if (focusedBlockId == blockId) focusedBlockId = null
        editor.remove(current, blockId)
    }

    /** Converts the focused block, or adds a new one of [type] if nothing is focused. */
    override fun onChangeType(type: BlockType) {
        val target = focusedBlockId
        if (target == null) onAddBlock(type) else edit { editor.changeType(it, target, type) }
    }

    override fun onTaskModeChange(mode: TaskCompletionMode) = edit { editor.setTaskMode(it, mode) }

    /**
     * Adds transcribed speech to [noteId]. Usually that's the open note; if the user has moved
     * on, it's written straight to storage. A note deleted as blank while the transcript was in
     * flight is recreated, so speech is never lost.
     */
    fun addTranscript(noteId: String, text: String) {
        if (note?.id == noteId) {
            edit { editor.appendTranscript(it, text) }
            return
        }
        persist {
            val base = repository.load(noteId) ?: Note(id = noteId)
            repository.save(editor.appendTranscript(base, text))
        }
    }

    override fun onFocusChanged(blockId: String, focused: Boolean) {
        if (focused) focusedBlockId = blockId
        else if (focusedBlockId == blockId) focusedBlockId = null
    }

    override fun onFocusConsumed() {
        pendingFocus = null
    }

    /** Saves any unsaved edit now. Call when the UI goes to the background. */
    fun flush() {
        saveJob?.cancel()
        val current = note ?: return
        if (!dirty) return
        dirty = false
        persist { repository.save(current) }
    }

    /** UNDISPATCHED reaches the (FIFO) mutex immediately, so writes land in call order. */
    private fun persist(write: suspend () -> Unit) {
        persistScope.launch(start = CoroutineStart.UNDISPATCHED) { saveMutex.withLock { write() } }
    }

    override fun onCleared() = flush()

    private inline fun edit(change: (Note) -> Note) {
        val current = note ?: return
        val updated = change(current)
        if (updated == current) return
        note = updated
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(saveDelayMs)
            flush()
        }
    }
}
