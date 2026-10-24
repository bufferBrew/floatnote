package com.bufferbrew.floatnote.voice

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class WhisperClientTest {

    private val server = MockWebServer()
    private lateinit var audio: File
    private val client = WhisperClient()

    @Before
    fun setUp() {
        server.start()
        audio = File.createTempFile("clip", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        audio.delete()
    }

    private fun config(language: String = "") = WhisperConfig(
        apiKey = "test-key",
        baseUrl = server.url("/v1/").toString(),
        model = "whisper-1",
        language = language,
    )

    @Test
    fun `sends a multipart request with the key in the header and returns trimmed text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"  buy milk  "}"""))

        assertEquals("buy milk", client.transcribe(audio, config(language = "en")))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"model\"") && body.contains("whisper-1"))
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("filename=\"${audio.name}\""))
        assertFalse("key must never be in the URL", request.requestUrl.toString().contains("test-key"))
    }

    @Test
    fun `language is omitted when blank`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hi"}"""))
        client.transcribe(audio, config())
        assertFalse(server.takeRequest().body.readUtf8().contains("name=\"language\""))
    }

    @Test
    fun `rejected key gives a clear message without echoing the server detail`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided: test-***"}}""")
        )
        val message = failureMessage()
        assertTrue(message.contains("API key was rejected"))
        assertFalse(message.contains("test-"))
    }

    @Test
    fun `other errors include the server's explanation`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Audio file is too short."}}""")
        )
        val message = failureMessage()
        assertTrue(message.contains("HTTP 400"))
        assertTrue(message.contains("too short"))
    }

    @Test
    fun `garbled reply is reported, not crashed on`() = runTest {
        server.enqueue(MockResponse().setBody("not json"))
        assertTrue(failureMessage().contains("Unexpected reply"))
    }

    private suspend fun failureMessage(): String {
        try {
            client.transcribe(audio, config())
        } catch (e: TranscriptionException) {
            return e.message.orEmpty()
        }
        fail("expected TranscriptionException")
        return ""
    }
}
