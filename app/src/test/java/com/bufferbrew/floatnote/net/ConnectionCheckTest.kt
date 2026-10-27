package com.bufferbrew.floatnote.net

import com.bufferbrew.floatnote.ai.AiException
import com.bufferbrew.floatnote.ai.ChatModel
import com.bufferbrew.floatnote.util.TestStrings
import com.bufferbrew.floatnote.voice.LlmConfig
import com.bufferbrew.floatnote.voice.WhisperConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCheckTest {
    private val server = MockWebServer().apply { start() }
    private var chatModels = mutableListOf<String>()
    private var chatFails: String? = null
    private val chat = object : ChatModel {
        override suspend fun complete(system: String, user: String, config: LlmConfig, json: Boolean): String {
            chatModels += config.model
            chatFails?.let { throw AiException(it) }
            return "OK"
        }
    }
    private val check = ConnectionCheck(TestStrings, chat)
    private val config get() = WhisperConfig(apiKey = "sk-test", baseUrl = server.url("/v1").toString(), model = "whisper-1", language = "")

    @After
    fun tearDown() = server.shutdown()

    private fun models(vararg ids: String) = MockResponse().setBody(
        """{"data":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}"""
    )

    @Test
    fun `all good - key accepted, speech model offered, AI model answers`() = runTest {
        server.enqueue(models("whisper-1", "gpt-4o-mini"))
        val lines = check.run(config, null, "gpt-4o-mini")
        assertEquals(listOf(true, true, true), lines.map { it.ok })
        assertEquals(listOf("gpt-4o-mini"), chatModels)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        assertTrue(lines.none { it.detail.contains("sk-test") })
    }

    @Test
    fun `a missing speech model and a failing AI model are reported separately`() = runTest {
        server.enqueue(models("gpt-4o-mini"))
        chatFails = "AI model or endpoint not found."
        val lines = check.run(config, null, "gpt-nope")
        assertEquals(listOf(true, false, false), lines.map { it.ok })
        assertEquals("AI model or endpoint not found.", lines[2].detail)
    }

    @Test
    fun `a rejected key stops the test before the AI request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val lines = check.run(config, null, "gpt-4o-mini")
        assertEquals(1, lines.size)
        assertEquals(false, lines[0].ok)
        assertTrue(lines[0].detail.contains("API key"))
        assertTrue(chatModels.isEmpty())
    }

    @Test
    fun `a server without a model list still tests the AI model`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val lines = check.run(config, null, "llama")
        assertEquals(true, lines[0].ok)
        assertNull(lines[1].ok)
        assertEquals(true, lines[2].ok)
    }

    @Test
    fun `unusable settings say why without calling anything`() = runTest {
        val lines = check.run(null, "Add your key.", "gpt-4o-mini")
        assertEquals(listOf(false), lines.map { it.ok })
        assertEquals("Add your key.", lines[0].detail)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unreachable server is reported`() = runTest {
        val gone = config
        server.shutdown()
        val lines = check.run(gone, null, "gpt-4o-mini")
        assertEquals(listOf(false), lines.map { it.ok })
        assertTrue(chatModels.isEmpty())
    }
}
