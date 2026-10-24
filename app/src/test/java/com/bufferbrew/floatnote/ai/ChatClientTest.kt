package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.util.TestStrings
import com.bufferbrew.floatnote.voice.LlmConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = ChatClient(TestStrings)
    private fun config(base: String = server.url("/v1").toString()) = LlmConfig("sk-test", base, "gpt-4o-mini")

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `sends system and user messages and returns the reply`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"  Hello there.  "}}]}"""))
        val reply = client.complete("sys", "um hello there", config(), json = true)
        assertEquals("Hello there.", reply)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("um hello there", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun `401 asks to check the key and points to settings`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key sk-test"}}"""))
        try {
            client.complete("s", "u", config())
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.needsSettings)
            assertTrue(e.message!!.contains("API key"))
            assertTrue(!e.message!!.contains("sk-test"))
        }
    }

    @Test
    fun `an address without a scheme is an error, not a crash`() = runTest {
        try {
            client.complete("s", "u", config("api.openai.com/v1"))
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.needsSettings)
        }
    }
}
