package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorTest {

    private var counter = 0
    private val editor = NoteEditor { "new${++counter}" }

    private fun task(id: String, done: Boolean = false) = Block.Task(id, id, done)
    private fun text(id: String) = Block.Text(id, id)

    private fun note(vararg blocks: Block, mode: TaskCompletionMode = TaskCompletionMode.MoveToBottom) =
        Note(id = "n", blocks = blocks.toList(), taskMode = mode)

    private fun Note.ids() = blocks.map { it.id }

    @Test
    fun `checklist toggle strikes in place`() {
        val start = note(text("a"), Block.Checklist("c", "c"), text("b"))
        val result = editor.toggle(start, "c")
        assertEquals(listOf("a", "c", "b"), result.ids())
        assertTrue((result.blocks[1] as Block.Checklist).checked)
    }

    @Test
    fun `move to bottom sends done task to end of its group`() {
        val start = note(task("a"), task("b"), task("c"), text("t"), task("d"))
        val result = editor.toggle(start, "a")
        assertEquals(listOf("b", "c", "a", "t", "d"), result.ids())
        assertTrue((result.blocks[2] as Block.Task).done)
    }

    @Test
    fun `done tasks stack in completion order below open ones`() {
        val start = note(task("a"), task("b"), task("c"))
        val result = editor.toggle(editor.toggle(start, "a"), "b")
        assertEquals(listOf("c", "a", "b"), result.ids())
    }

    @Test
    fun `unchecking returns task to just after the last open task`() {
        val start = note(task("a"), task("b", done = true), task("c", done = true))
        val result = editor.toggle(start, "c")
        assertEquals(listOf("a", "c", "b"), result.ids())
        assertTrue(!(result.blocks[1] as Block.Task).done)
    }

    @Test
    fun `unchecking with no open tasks moves to group start`() {
        val start = note(text("t"), task("a", done = true), task("b", done = true))
        val result = editor.toggle(start, "b")
        assertEquals(listOf("t", "b", "a"), result.ids())
    }

    @Test
    fun `single task group toggles in place`() {
        val start = note(text("x"), task("a"), text("y"))
        assertEquals(listOf("x", "a", "y"), editor.toggle(start, "a").ids())
    }

    @Test
    fun `keep in place only flips done`() {
        val start = note(task("a"), task("b"), mode = TaskCompletionMode.KeepInPlace)
        val result = editor.toggle(start, "a")
        assertEquals(listOf("a", "b"), result.ids())
        assertTrue((result.blocks[0] as Block.Task).done)
    }

    @Test
    fun `switching to move to bottom sorts each group stably`() {
        val start = note(
            task("a", done = true), task("b"), task("c", done = true), task("d"),
            text("t"),
            task("e", done = true), task("f"),
            mode = TaskCompletionMode.KeepInPlace,
        )
        val result = editor.setTaskMode(start, TaskCompletionMode.MoveToBottom)
        assertEquals(listOf("b", "d", "a", "c", "t", "f", "e"), result.ids())
        assertEquals(TaskCompletionMode.MoveToBottom, result.taskMode)
    }

    @Test
    fun `insert after places block and returns its id`() {
        val (result, id) = editor.insertAfter(note(text("a"), text("b")), "a", BlockType.Task)
        assertEquals(listOf("a", "new1", "b"), result.ids())
        assertEquals(id, "new1")
        assertTrue(result.blocks[1] is Block.Task)
    }

    @Test
    fun `new task added after a done task joins the open ones`() {
        val start = note(task("a"), task("b", done = true), task("c", done = true))
        val (result, id) = editor.insertAfter(start, "c", BlockType.Task)
        assertEquals(listOf("a", id, "b", "c"), result.ids())
    }

    @Test
    fun `split of a done task puts the new open task above done ones`() {
        val start = note(task("a"), task("b", done = true))
        val (result, id) = editor.split(start, "b", "b", "next")
        assertEquals(listOf("a", id, "b"), result.ids())
        assertEquals("next", result.blocks[1].text)
    }

    @Test
    fun `new task after an open task stays right below it`() {
        val start = note(task("a"), task("b"), task("c", done = true))
        val (result, id) = editor.insertAfter(start, "a", BlockType.Task)
        assertEquals(listOf("a", id, "b", "c"), result.ids())
    }

    @Test
    fun `converting a line to a task sorts it into its group`() {
        val start = note(task("a", done = true), text("t"))
        assertEquals(listOf("t", "a"), editor.changeType(start, "t", BlockType.Task).ids())
    }

    @Test
    fun `removing the line between two lists merges and sorts them`() {
        val start = note(task("a"), task("x", done = true), text("t"), task("b"))
        assertEquals(listOf("a", "b", "x"), editor.remove(start, "t").ids())
    }

    @Test
    fun `keep in place never re-sorts on structural edits`() {
        val start = note(task("a", done = true), mode = TaskCompletionMode.KeepInPlace)
        val (result, id) = editor.insertAfter(start, "a", BlockType.Task)
        assertEquals(listOf("a", id), result.ids())
    }

    @Test
    fun `insert with null anchor appends`() {
        val (result, _) = editor.insertAfter(note(text("a")), null, BlockType.Text)
        assertEquals(listOf("a", "new1"), result.ids())
    }

    @Test
    fun `change type keeps id and text`() {
        val start = note(Block.Checklist("c", "milk", checked = true))
        val result = editor.changeType(start, "c", BlockType.Task)
        assertEquals(Block.Task("c", "milk", done = false), result.blocks.single())
    }

    @Test
    fun `update text and remove`() {
        val edited = editor.updateText(note(task("a"), text("b")), "a", "hello")
        assertEquals("hello", edited.blocks[0].text)
        assertEquals(listOf("b"), editor.remove(edited, "a").ids())
    }

    @Test
    fun `append transcript trims and skips blank`() {
        val start = note(text("a"))
        assertSame(start, editor.appendTranscript(start, "   "))
        val result = editor.appendTranscript(start, "  buy milk \n")
        assertEquals(Block.Text("new1", "buy milk"), result.blocks.last())
    }

    @Test
    fun `unknown id leaves note unchanged`() {
        val start = note(task("a"))
        assertSame(start, editor.toggle(start, "missing"))
        assertSame(start, editor.updateText(start, "missing", "x"))
    }
}
