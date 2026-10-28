package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.util.TestStrings
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `sends the category, and takes the reply's only when the note has none`() {
        assertEquals("brew", JSONObject(NoteFormat.toJson(base.copy(category = NoteCategory.BrewProjects))).getString("category"))
        assertEquals("", JSONObject(NoteFormat.toJson(base)).getString("category"))
        val reply = """{"category":"github","blocks":[{"type":"text","text":"x"}]}"""
        assertEquals(NoteCategory.PersonalGithub, NoteFormat.fromJson(reply, base, newId, TestStrings).category)
        val mine = base.copy(category = NoteCategory.Work)
        assertEquals(NoteCategory.Work, NoteFormat.fromJson(reply, mine, newId, TestStrings).category)
        val unknown = """{"category":"hobbies","blocks":[{"type":"text","text":"x"}]}"""
        assertEquals(null, NoteFormat.fromJson(unknown, base, newId, TestStrings).category)
    }

    @Test
    fun `suggested points are appended, marked, de-duplicated and capped`() {
        val reply = """{"blocks":[{"type":"task","text":"Call mum"}],
            "added":["Book the dentist", "", "call MUM", "✦ Book the dentist", "Check the budget", "Ask Sam", "One more"]}"""
        val note = NoteFormat.fromJson(reply, base, newId, TestStrings, maxAdded = 3)
        assertEquals(
            listOf("Call mum", "✦ Book the dentist", "✦ Check the budget", "✦ Ask Sam"),
            note.blocks.map { it.text },
        )
        assertTrue(note.blocks.drop(1).all { it is Block.Text })
        // Off unless asked for.
        assertEquals(listOf("Call mum"), NoteFormat.fromJson(reply, base, newId, TestStrings).blocks.map { it.text })
    }

    @Test
    fun `a summary goes on top and new action items become tasks at the end`() {
        val reply = """{"summary":"Errands for the weekend.","actions":["Buy milk","Pay rent","Book the vet","book the vet",""]}"""
        val out = NoteFormat.summaryFromJson(reply, base.copy(blocks = base.blocks + Block.Task("c", "Buy milk")), newId, TestStrings)
        assertEquals(NoteFormat.SUMMARY_MARK + "Errands for the weekend.", out.blocks.first().text)
        // Existing blocks are untouched; only "Book the vet" is new.
        assertEquals(listOf("a", "b", "c"), out.blocks.drop(1).take(3).map { it.id })
        assertEquals(listOf("Book the vet"), out.blocks.drop(4).map { it.text })
        assertTrue(out.blocks.last() is Block.Task)
    }

    @Test
    fun `summarising again replaces the earlier summary`() {
        val summarised = base.copy(blocks = listOf(Block.Text("s", NoteFormat.SUMMARY_MARK + "old")) + base.blocks)
        val out = NoteFormat.summaryFromJson("""{"summary":"new","actions":[]}""", summarised, newId, TestStrings)
        assertEquals(listOf(NoteFormat.SUMMARY_MARK + "new", "um buy milk and call mum", "pay rent"), out.blocks.map { it.text })
    }

    @Test
    fun `an empty summary reply leaves the note alone`() {
        assertThrows(AiException::class.java) {
            NoteFormat.summaryFromJson("""{"summary":"","actions":[]}""", base, newId, TestStrings)
        }
        assertThrows(AiException::class.java) { NoteFormat.summaryFromJson("not json", base, newId, TestStrings) }
    }
}
