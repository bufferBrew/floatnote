package com.bufferbrew.floatnote.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.ai.AiException
import com.bufferbrew.floatnote.util.Strings
import com.bufferbrew.floatnote.ai.NoteOrganiser
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
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
    private val strings: Strings,
    private val editor: NoteEditor = NoteEditor(),
    private val saveDelayMs: Long = 400,
    /** The AI "Organise" step. Null only in tests that don't need it. */
    private val organiser: NoteOrganiser? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel(), NoteEditorActions {

    /** All notes (pinned first), for the floating panel's note switcher. */
    val notes: Flow<List<NoteSummary>> = repository.observeSummaries()

    /** What the list's search box holds. */
    var query by mutableStateOf("")

    /** The list screen's notes: everything, or what matches [query]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: Flow<List<NoteSummary>> =
        snapshotFlow { query }.flatMapLatest { repository.observeSummaries(it) }

    /** The open note, or null when the list should show. */
    var note by mutableStateOf<Note?>(null)
        private set

    /** Block the UI should move focus to once, then report back via [onFocusConsumed]. */
    var pendingFocus by mutableStateOf<String?>(null)
        private set

    /** The open note's Organise step. */
    var assist by mutableStateOf<AssistState>(AssistState.Idle)
        private set

    private var organiseJob: Job? = null

    /** The note as it was before Organise, for Undo. */
    private var beforeOrganise: Note? = null
    private var organisedIds: Set<String> = emptySet()

    /**
     * Undo/redo for the open note: snapshots of earlier versions. Typing into one line within
     * [TYPING_GROUP_MS] counts as one step, so Undo removes a word or a phrase, not a letter.
     */
    private val undoStack = ArrayDeque<Note>()
    private val redoStack = ArrayDeque<Note>()
    private var lastTypedBlock: String? = null
    private var lastTypedAt = 0L

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** The note and paragraph the current dictation is writing into. */
    private var dictationBlock: Pair<String, String>? = null

    /** Block that currently has focus, used as the anchor for new blocks. */
    private var focusedBlockId: String? = null
    private var saveJob: Job? = null
    private var dirty = false
    private val saveMutex = Mutex()

    fun open(noteId: String) {
        if (note?.id == noteId) return
        closeNote()
        // Through the save lock: closing then quickly reopening a note must read the saved copy,
        // not the one from before the close's save landed.
        viewModelScope.launch { note = saveMutex.withLock { repository.load(noteId) } }
    }

    fun createNote() {
        closeNote()
        val created = editor.newNote()
        note = created
        pendingFocus = created.blocks.first().id
    }

    /** Opens a new note holding text shared from another app, and saves it straight away. */
    fun createNote(title: String, text: String) {
        closeNote()
        val created = editor.newNote(title, text)
        note = created
        if (!created.isBlank) {
            dirty = true
            flush()
        }
    }

    /** Opens the last-edited note, or a new one, unless a note is already open. */
    fun ensureOpen() {
        if (note != null) return
        viewModelScope.launch {
            if (note == null) note = saveMutex.withLock { repository.mostRecent() } ?: editor.newNote()
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
        resetAssist()
        clearHistory()
    }

    /** The note deleted last, offered for Undo on the list until [clearDeleted]. */
    var recentlyDeleted by mutableStateOf<Note?>(null)
        private set

    /** Deletes now, but keeps the note in memory so [undoDelete] can bring it back. */
    fun deleteNote(noteId: String) {
        val open = note?.takeIf { it.id == noteId }
        if (open != null) {
            saveJob?.cancel()
            dirty = false
            note = null
            resetAssist()
        }
        viewModelScope.launch {
            // The open copy has edits that may not be saved yet; otherwise read it before it goes.
            val deleted = open ?: repository.load(noteId)
            persist { repository.delete(noteId) }
            recentlyDeleted = deleted?.takeUnless { it.isBlank }
        }
    }

    /** Pins or unpins without changing "last edited". The open note is changed in memory too. */
    fun setPinned(noteId: String, pinned: Boolean) {
        if (note?.id == noteId) {
            edit { it.copy(pinned = pinned) }
            flush()
        } else {
            persist { repository.setPinned(noteId, pinned) }
        }
    }

    fun undoDelete() {
        val restored = recentlyDeleted ?: return
        recentlyDeleted = null
        persist { repository.save(restored) }
    }

    fun clearDeleted() {
        recentlyDeleted = null
    }

    override fun onTitleChange(title: String) = edit(typingIn = TITLE_KEY) { editor.setTitle(it, title) }

    override fun onToggle(blockId: String) = edit { editor.toggle(it, blockId) }

    /** A newline typed into a list item splits it into two items, like Enter in Keep. */
    override fun onTextChange(blockId: String, text: String) = edit(typingIn = blockId) { current ->
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
    fun addTranscript(noteId: String, text: String, continues: Boolean = false) {
        if (note?.id == noteId) {
            val target = dictationBlock?.takeIf { continues && it.first == noteId }?.second
            edit { current ->
                if (target != null) editor.continueTranscript(current, target, text)
                else editor.appendTranscript(current, text)
            }
            note?.blocks?.lastOrNull()?.let { dictationBlock = noteId to it.id }
            return
        }
        persist {
            val base = repository.load(noteId) ?: Note(id = noteId)
            repository.save(editor.appendTranscript(base, text))
        }
    }

    /**
     * Sends the note to the AI model and replaces its content with the organised version.
     * Lines dictated while it was working are kept (added after the result). If you edited the
     * note in the meantime, the result is dropped rather than overwrite your edit.
     */
    override fun onOrganise() {
        val snapshot = note ?: return
        if (assist == AssistState.Working) return
        val organiser = organiser ?: return
        // Cancelled by closing the note (resetAssist), so a late result can't land in another note.
        assist = AssistState.Working
        organiseJob = viewModelScope.launch {
            val result = try {
                organiser.organise(snapshot)
            } catch (e: AiException) {
                assist = AssistState.Error(e.message ?: strings.get(R.string.err_organise_failed), e.needsSettings)
                return@launch
            }
            val current = note
            if (current == null || current.id != snapshot.id) return@launch
            val snapshotIds = snapshot.blocks.map { it.id }.toSet()
            val edited = current.title != snapshot.title ||
                current.blocks.filter { it.id in snapshotIds }.toSet() != snapshot.blocks.toSet() ||
                current.blocks.count { it.id in snapshotIds } != snapshot.blocks.size
            if (edited) {
                assist = AssistState.Error(strings.get(R.string.err_organise_edited))
                return@launch
            }
            val added = current.blocks.filter { it.id !in snapshotIds }
            beforeOrganise = current
            organisedIds = result.blocks.map { it.id }.toSet()
            edit { result.copy(blocks = result.blocks + added) }
            assist = AssistState.Done
        }
    }

    /** Restores the note from before Organise, keeping anything dictated since. */
    override fun onUndoOrganise() {
        val before = beforeOrganise ?: return
        edit { current ->
            val beforeIds = before.blocks.map { it.id }.toSet()
            val since = current.blocks.filter { it.id !in organisedIds && it.id !in beforeIds }
            before.copy(blocks = before.blocks + since)
        }
        resetAssist()
    }

    override fun onDismissAssist() = resetAssist()

    private fun resetAssist() {
        organiseJob?.cancel()
        organiseJob = null
        beforeOrganise = null
        organisedIds = emptySet()
        assist = AssistState.Idle
    }

    override fun onUndo() {
        val previous = undoStack.removeLastOrNull() ?: return
        val current = note ?: return
        redoStack.addLast(current)
        restore(previous)
    }

    override fun onRedo() {
        val next = redoStack.removeLastOrNull() ?: return
        val current = note ?: return
        undoStack.addLast(current)
        restore(next)
    }

    private fun restore(version: Note) {
        note = version
        lastTypedBlock = null
        if (focusedBlockId != null && version.blocks.none { it.id == focusedBlockId }) focusedBlockId = null
        markDirty()
        updateUndoFlags()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        lastTypedBlock = null
        updateUndoFlags()
    }

    private fun updateUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** From History: put back what was heard in place of the cleaned-up text. */
    fun useOriginal(noteId: String, cleaned: String, raw: String) {
        if (note?.id == noteId) {
            edit { editor.replaceLast(it, cleaned, raw) }
            return
        }
        persist {
            val base = repository.load(noteId) ?: return@persist
            val changed = editor.replaceLast(base, cleaned, raw)
            if (changed != base) repository.save(changed)
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

    /** Suspends until every save queued so far has been written (for export). */
    suspend fun awaitSaves() = saveMutex.withLock { }

    /** UNDISPATCHED reaches the (FIFO) mutex immediately, so writes land in call order. */
    private fun persist(write: suspend () -> Unit) {
        persistScope.launch(start = CoroutineStart.UNDISPATCHED) { saveMutex.withLock { write() } }
    }

    override fun onCleared() = flush()

    /** [typingIn]: the line being typed into, so a run of keystrokes is one undo step. */
    private inline fun edit(typingIn: String? = null, change: (Note) -> Note) {
        val current = note ?: return
        val updated = change(current)
        if (updated == current) return
        val now = clock()
        val sameRun = typingIn != null && typingIn == lastTypedBlock && now - lastTypedAt < TYPING_GROUP_MS
        if (!sameRun) {
            undoStack.addLast(current)
            if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        }
        redoStack.clear()
        lastTypedBlock = typingIn
        lastTypedAt = now
        note = updated
        updateUndoFlags()
        markDirty()
    }

    private fun markDirty() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(saveDelayMs)
            flush()
        }
    }

    private companion object {
        const val TITLE_KEY = "title"
        const val TYPING_GROUP_MS = 1_500L
        const val UNDO_LIMIT = 100
    }
}
