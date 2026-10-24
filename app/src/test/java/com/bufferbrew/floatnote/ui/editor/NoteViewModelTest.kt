package com.bufferbrew.floatnote.ui.editor

import com.bufferbrew.floatnote.util.TestStrings
import com.bufferbrew.floatnote.data.NoteRepository
import com.bufferbrew.floatnote.domain.NoteEditor
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModelTest {

    private class FakeRepository(private val initial: Note) : NoteRepository {
        val saved = mutableListOf<Note>()
        val deleted = mutableListOf<String>()
        override fun observeSummaries(query: String) = emptyFlow<List<NoteSummary>>()
        val pins = mutableListOf<Pair<String, Boolean>>()
        override suspend fun setPinned(id: String, pinned: Boolean) { pins += id to pinned }
        override suspend fun all() = listOf(initial)
        override suspend fun load(id: String) = initial.takeIf { it.id == id }
        override suspend fun mostRecent() = initial
        override suspend fun save(note: Note) { saved += note }
        override suspend fun delete(id: String) { deleted += id }
    }

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var counter = 0

    private val start = Note(
        id = "n",
        blocks = listOf(Block.Task("a", "first"), Block.Task("b", "second")),
    )
    private val repository = FakeRepository(start)

    private fun viewModel() =
        NoteViewModel(repository, scope, TestStrings, NoteEditor { "new${++counter}" }, saveDelayMs = 400)
            .also {
                it.ensureOpen()
                scope.runCurrent() // finish the load
            }

    private val organiseGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var organiseResult: (Note) -> Note = { it.copy(title = "Organised", blocks = listOf(Block.Text("o1", "tidy"))) }

    private fun organisingViewModel() =
        NoteViewModel(
            repository, scope, TestStrings, NoteEditor { "new${++counter}" }, saveDelayMs = 400,
            organiser = { note ->
                organiseGate.await()
                organiseResult(note)
            },
        ).also {
            it.ensureOpen()
            scope.runCurrent()
        }

    @Test
    fun `organise replaces the note and undo restores it`() {
        val vm = organisingViewModel()
        vm.onOrganise()
        scope.runCurrent()
        assertEquals(AssistState.Working, vm.assist)
        organiseGate.complete(Unit)
        scope.runCurrent()
        assertEquals(AssistState.Done, vm.assist)
        assertEquals("Organised", vm.note?.title)
        assertEquals(listOf(Block.Text("o1", "tidy")), vm.note?.blocks)
        vm.onUndoOrganise()
        assertEquals(start, vm.note)
        assertEquals(AssistState.Idle, vm.assist)
    }

    @Test
    fun `speech added while organising is kept, and survives undo`() {
        val vm = organisingViewModel()
        vm.onOrganise()
        scope.runCurrent()
        vm.addTranscript("n", "said meanwhile")
        organiseGate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("tidy", "said meanwhile"), vm.note?.blocks?.map { it.text })
        vm.addTranscript("n", "said after")
        vm.onUndoOrganise()
        assertEquals(listOf("first", "second", "said meanwhile", "said after"), vm.note?.blocks?.map { it.text })
    }

    @Test
    fun `an edit while organising wins over the result`() {
        val vm = organisingViewModel()
        vm.onOrganise()
        scope.runCurrent()
        vm.onTextChange("a", "edited")
        organiseGate.complete(Unit)
        scope.runCurrent()
        assertTrue(vm.assist is AssistState.Error)
        assertEquals("edited", vm.note?.blocks?.first()?.text)
    }

    @Test
    fun `an organise failure leaves the note alone`() {
        organiseResult = { throw com.bufferbrew.floatnote.ai.AiException("nope", needsSettings = true) }
        val vm = organisingViewModel()
        vm.onOrganise()
        organiseGate.complete(Unit)
        scope.runCurrent()
        assertEquals(AssistState.Error("nope", needsSettings = true), vm.assist)
        assertEquals(start, vm.note)
    }

    @Test
    fun `closing the note drops a pending organise`() {
        val vm = organisingViewModel()
        vm.onOrganise()
        scope.runCurrent()
        vm.closeNote()
        organiseGate.complete(Unit)
        scope.runCurrent()
        assertNull(vm.note)
        assertEquals(AssistState.Idle, vm.assist)
    }

    private var now = 0L

    private fun timedViewModel() =
        NoteViewModel(repository, scope, TestStrings, NoteEditor { "new${++counter}" }, saveDelayMs = 400, clock = { now })
            .also {
                it.ensureOpen()
                scope.runCurrent()
            }

    @Test
    fun `undo and redo step through edits, grouping quick typing in one line`() {
        val vm = timedViewModel()
        assertTrue(!vm.canUndo)
        vm.onTextChange("a", "f"); now += 100
        vm.onTextChange("a", "fi"); now += 100
        vm.onTextChange("a", "fix")
        vm.onToggle("b")
        vm.onUndo()
        assertEquals("fix", vm.note!!.blocks.first { it.id == "a" }.text)
        vm.onUndo()
        assertEquals(start, vm.note)
        assertTrue(!vm.canUndo)
        vm.onRedo()
        assertEquals("fix", vm.note!!.blocks.first { it.id == "a" }.text)
        assertTrue(vm.canRedo)
        vm.onTextChange("a", "fixed")
        assertTrue("a new edit clears redo", !vm.canRedo)
    }

    @Test
    fun `typing after a pause is a separate undo step`() {
        val vm = timedViewModel()
        vm.onTextChange("a", "one")
        now += 5_000
        vm.onTextChange("a", "one two")
        vm.onUndo()
        assertEquals("one", vm.note!!.blocks.first { it.id == "a" }.text)
    }

    @Test
    fun `undo history is per note`() {
        val vm = timedViewModel()
        vm.onToggle("a")
        vm.closeNote()
        vm.createNote()
        assertTrue(!vm.canUndo)
    }

    @Test
    fun `a dictation's sentences join one paragraph`() {
        val vm = viewModel()
        vm.addTranscript("n", "Hello there.")
        vm.addTranscript("n", "How are you?", continues = true)
        vm.addTranscript("n", "New dictation.")
        assertEquals(listOf("first", "second", "Hello there. How are you?", "New dictation."), vm.note?.blocks?.map { it.text })
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the most recent note`() {
        assertEquals(start, viewModel().note)
    }

    @Test
    fun `rapid edits are saved once after the quiet period`() {
        val vm = viewModel()
        vm.onTextChange("a", "f")
        vm.onTextChange("a", "fi")
        scope.advanceTimeBy(399)
        assertTrue(repository.saved.isEmpty())
        scope.advanceTimeBy(2)
        assertEquals(listOf("fi"), repository.saved.map { it.blocks[0].text })
    }

    @Test
    fun `flush saves immediately and only when dirty`() {
        val vm = viewModel()
        vm.flush()
        scope.runCurrent()
        assertTrue(repository.saved.isEmpty())

        vm.onToggle("a")
        vm.flush()
        scope.runCurrent()
        assertEquals(1, repository.saved.size)
        scope.advanceTimeBy(1_000) // debounced save was cancelled by the flush
        assertEquals(1, repository.saved.size)
    }

    @Test
    fun `newline in a list item splits it and focuses the new item`() {
        val vm = viewModel()
        vm.onTextChange("a", "fir\nst")
        val blocks = vm.note!!.blocks
        assertEquals(listOf("fir", "st", "second"), blocks.map { it.text })
        assertTrue(blocks[1] is Block.Task)
        assertEquals("new1", vm.pendingFocus)
    }

    @Test
    fun `new block goes after the focused one`() {
        val vm = viewModel()
        vm.onFocusChanged("a", focused = true)
        vm.onAddBlock(BlockType.Checklist)
        assertEquals(listOf("a", "new1", "b"), vm.note!!.blocks.map { it.id })
    }

    @Test
    fun `change type converts the focused block`() {
        val vm = viewModel()
        vm.onFocusChanged("b", focused = true)
        vm.onChangeType(BlockType.Text)
        assertEquals(Block.Text("b", "second"), vm.note!!.blocks[1])
    }

    @Test
    fun `removing a block focuses the one above`() {
        val vm = viewModel()
        vm.onRemove("b")
        assertEquals(listOf("a"), vm.note!!.blocks.map { it.id })
        assertEquals("a", vm.pendingFocus)
    }

    @Test
    fun `closing an edited note saves it and returns to the list`() {
        val vm = viewModel()
        vm.onTextChange("a", "changed")
        vm.closeNote()
        scope.runCurrent()
        assertNull(vm.note)
        assertEquals("changed", repository.saved.single().blocks[0].text)
    }

    @Test
    fun `closing a blank new note deletes it instead of saving`() {
        val vm = viewModel()
        vm.closeNote()
        vm.createNote()
        val createdId = vm.note!!.id
        vm.closeNote()
        scope.advanceTimeBy(1_000)
        assertEquals(listOf(createdId), repository.deleted)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `new note focuses its first line`() {
        val vm = viewModel()
        vm.createNote()
        assertEquals(vm.note!!.blocks.single().id, vm.pendingFocus)
    }

    @Test
    fun `deleting the open note closes it without saving`() {
        val vm = viewModel()
        vm.onToggle("a")
        vm.deleteNote("n")
        scope.advanceTimeBy(1_000)
        assertNull(vm.note)
        assertEquals(listOf("n"), repository.deleted)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `undo after deleting the open note restores it with unsaved edits`() {
        val vm = viewModel()
        vm.onToggle("a")
        val edited = vm.note
        vm.deleteNote("n")
        scope.runCurrent()
        assertEquals(edited, vm.recentlyDeleted)
        vm.undoDelete()
        scope.runCurrent()
        assertEquals(listOf(edited), repository.saved)
        assertNull(vm.recentlyDeleted)
    }

    @Test
    fun `deleting from the list loads the note first so it can be undone`() {
        val vm = viewModel()
        vm.closeNote()
        scope.advanceTimeBy(1_000)
        vm.deleteNote("n")
        scope.runCurrent()
        assertEquals(listOf("n"), repository.deleted)
        assertEquals(start, vm.recentlyDeleted)
        vm.clearDeleted()
        assertNull(vm.recentlyDeleted)
    }

    @Test
    fun `shared text opens as a new saved note`() {
        val vm = viewModel()
        vm.createNote("From Chrome", "  an article link  ")
        scope.runCurrent()
        assertEquals("From Chrome", vm.note?.title)
        assertEquals("an article link", vm.note?.blocks?.single()?.text)
        assertEquals(vm.note, repository.saved.last())
    }

    @Test
    fun `open loads the requested note`() {
        val vm = viewModel()
        vm.closeNote()
        vm.open("n")
        scope.runCurrent()
        assertEquals(start, vm.note)
    }

    @Test
    fun `transcript goes into the open note`() {
        val vm = viewModel()
        vm.addTranscript("n", "spoken words")
        assertEquals("spoken words", vm.note!!.blocks.last().text)
    }

    @Test
    fun `transcript for a closed note is saved to storage`() {
        val vm = viewModel()
        vm.closeNote()
        vm.addTranscript("n", "later words")
        scope.runCurrent()
        assertEquals("later words", repository.saved.last().blocks.last().text)
        assertEquals("n", repository.saved.last().id)
    }

    @Test
    fun `transcript for a note deleted as blank recreates it`() {
        val vm = viewModel()
        vm.addTranscript("gone", "rescued words")
        scope.runCurrent()
        val saved = repository.saved.last()
        assertEquals("gone", saved.id)
        assertEquals(listOf("rescued words"), saved.blocks.map { it.text })
    }
}
