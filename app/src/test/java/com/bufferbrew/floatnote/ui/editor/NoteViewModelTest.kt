package com.bufferbrew.floatnote.ui.editor

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
        override fun observeSummaries() = emptyFlow<List<NoteSummary>>()
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
        NoteViewModel(repository, scope, NoteEditor { "new${++counter}" }, saveDelayMs = 400)
            .also {
                it.ensureOpen()
                scope.runCurrent() // finish the load
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
    fun `open loads the requested note`() {
        val vm = viewModel()
        vm.closeNote()
        vm.open("n")
        scope.runCurrent()
        assertEquals(start, vm.note)
    }
}
