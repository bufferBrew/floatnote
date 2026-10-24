package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.util.TestStrings
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteFormatTest {
    private var counter = 0
    private val newId = { "id${++counter}" }
    private val base = Note(
        id = "n",
        title = "",
        blocks = listOf(Block.Text("a", "um buy milk and call mum"), Block.Task("b", "pay rent", done = true)),
        taskMode = TaskCompletionMode.KeepInPlace,
    )

    @Test
    fun `serialises every non-blank block with its state`() {
        val json = JSONObject(NoteFormat.toJson(base.copy(blocks = base.blocks + Block.Text("c", " "))))
        val blocks = json.getJSONArray("blocks")
        assertEquals(2, blocks.length())
        assertEquals("task", blocks.getJSONObject(1).getString("type"))
        assertEquals(true, blocks.getJSONObject(1).getBoolean("checked"))
    }

    @Test
    fun `parses a reply, fenced or not, keeping note id and task mode`() {
        val reply = """```json
            {"title":"Errands","blocks":[
              {"type":"checklist","text":"Milk","checked":false},
              {"type":"task","text":"Call mum"},
              {"type":"task","text":"Pay rent","checked":true},
              {"type":"weird","text":"Note"},
              {"type":"text","text":"  "}]}
            ```"""
        val note = NoteFormat.fromJson(reply, base, newId, TestStrings)
        assertEquals("n", note.id)
        assertEquals("Errands", note.title)
        assertEquals(TaskCompletionMode.KeepInPlace, note.taskMode)
        assertEquals(
            listOf(
                Block.Checklist("id1", "Milk"),
                Block.Task("id2", "Call mum"),
                Block.Task("id3", "Pay rent", done = true),
                Block.Text("id4", "Note"),
            ),
            note.blocks,
        )
    }

    @Test
    fun `keeps the old title when the reply has none`() {
        val note = NoteFormat.fromJson("""{"blocks":[{"type":"text","text":"x"}]}""", base.copy(title = "Mine"), newId, TestStrings)
        assertEquals("Mine", note.title)
    }

    @Test
    fun `rejects garbage and empty replies instead of wiping the note`() {
        assertThrows(AiException::class.java) { NoteFormat.fromJson("Sure! Here you go", base, newId, TestStrings) }
        assertThrows(AiException::class.java) { NoteFormat.fromJson("""{"title":"x","blocks":[]}""", base, newId, TestStrings) }
        assertThrows(AiException::class.java) { NoteFormat.fromJson("""{"title":"x"}""", base, newId, TestStrings) }
    }
}
