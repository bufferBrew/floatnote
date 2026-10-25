package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.util.TestStrings
import com.bufferbrew.floatnote.voice.LlmConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAssistantTest {
    private class FakeModel(private val reply: String) : ChatModel {
        var system = ""
        override suspend fun complete(system: String, user: String, config: LlmConfig, json: Boolean): String {
            this.system = system
            return reply
        }
    }

    private val reply = """{"title":"Wire","category":"brew","blocks":[{"type":"task","text":"Fix the brief"}],
        "added":["Check the collector logs"]}"""
    private var counter = 0
    private var suggest = true

    private fun assistant(model: ChatModel, related: List<String> = emptyList()) = NoteAssistant(
        TestStrings,
        model,
        config = { LlmConfig("key", "https://api.example.com/v1", "m") },
        newId = { "id${++counter}" },
        categoryContext = { "context for ${it.key}" },
        relatedTitles = { related },
        addSuggestions = { suggest },
    )

    @Test
    fun `a categorised note sends that category's context and related titles only`() = runBlocking {
        val model = FakeModel(reply)
        val note = Note("n", blocks = listOf(Block.Text("a", "fix brief")), category = NoteCategory.BrewProjects)
        val organised = assistant(model, related = listOf("BrewWire sports", "NeuroBrew voices")).organise(note)
        assertTrue(model.system.contains("context for brew"))
        assertTrue(model.system.contains("\"BrewWire sports\", \"NeuroBrew voices\""))
        assertFalse(model.system.contains("context for work"))
        assertEquals(listOf("Fix the brief", "✦ Check the collector logs"), organised.blocks.map { it.text })
        assertEquals(NoteCategory.BrewProjects, organised.category)
    }

    @Test
    fun `an uncategorised note offers every category and takes the suggested one`() = runBlocking {
        val model = FakeModel(reply)
        val organised = assistant(model).organise(Note("n", blocks = listOf(Block.Text("a", "fix brief"))))
        NoteCategory.entries.forEach { assertTrue(model.system.contains("- ${it.key}: context for ${it.key}")) }
        assertEquals(NoteCategory.BrewProjects, organised.category)
    }

    @Test
    fun `with suggestions off nothing is added`() = runBlocking {
        suggest = false
        val model = FakeModel(reply)
        val organised = assistant(model).organise(Note("n", blocks = listOf(Block.Text("a", "fix brief"))))
        assertTrue(model.system.contains("\"added\": always []"))
        assertEquals(listOf("Fix the brief"), organised.blocks.map { it.text })
    }
}
