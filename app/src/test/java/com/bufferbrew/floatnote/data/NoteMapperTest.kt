package com.bufferbrew.floatnote.data

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMapperTest {

    private val note = Note(
        id = "n",
        title = "Groceries",
        blocks = listOf(
            Block.Text("a", "Shop"),
            Block.Checklist("b", "Milk", checked = true),
            Block.Task("c", "Eggs", done = true),
            Block.Task("d", "Bread"),
        ),
        taskMode = TaskCompletionMode.KeepInPlace,
    )

    @Test
    fun `round trip keeps every field and order`() {
        val restored = note.toEntity(updatedAt = 5).toDomain(note.toBlockEntities())
        assertEquals(note, restored)
    }

    @Test
    fun `blocks are restored by position, not row order`() {
        val shuffled = note.toBlockEntities().reversed()
        assertEquals(note.blocks, note.toEntity(0).toDomain(shuffled).blocks)
    }

    @Test
    fun `unknown enum names fall back to defaults`() {
        val entity = note.toEntity(0).copy(taskMode = "SomethingNew")
        val block = note.toBlockEntities().first().copy(type = "Drawing", text = "x")
        val restored = entity.toDomain(listOf(block))
        assertEquals(TaskCompletionMode.MoveToBottom, restored.taskMode)
        assertEquals(Block.Text("a", "x"), restored.blocks.single())
    }
}
