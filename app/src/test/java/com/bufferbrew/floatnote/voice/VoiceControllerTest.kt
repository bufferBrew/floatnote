package com.bufferbrew.floatnote.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerTest {

    private class FakeRecorder : AudioRecorder {
        var level = 0.5f
        var failStart = false
        var usable = true
        var recording: File? = null
        override fun start(output: File) {
            if (failStart) throw IllegalStateException("mic busy")
            output.writeBytes(byteArrayOf(1))
            recording = output
        }
        override fun level() = level
        override fun stop(): Boolean { recording = null; return usable }
    }

    private class FakeTranscriber : Transcriber {
        var reply: () -> String = { "hello world" }
        val sent = mutableListOf<File>()
        override suspend fun transcribe(audio: File, config: WhisperConfig): String {
            sent += audio
            return reply()
        }
    }

    private val dir: File = Files.createTempDirectory("voice").toFile()
    private val scope = TestScope(StandardTestDispatcher())
    private val recorder = FakeRecorder()
    private val transcriber = FakeTranscriber()
    private var config: WhisperConfig? = WhisperConfig("k", "https://example.test/v1", "whisper-1", "")
    private val transcripts = mutableListOf<Pair<String, String>>()

    private val voice = VoiceController(
        recorder = recorder,
        transcriber = transcriber,
        config = { config },
        audioDir = dir,
        scope = scope,
        onTranscript = { id, text -> transcripts += id to text },
        clock = { scope.testScheduler.currentTime },
        sampleIntervalMs = 100,
        maxDurationMs = 10_000,
    )

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `record then stop sends the clip and delivers text to the starting note`() {
        voice.start("note-1")
        assertTrue(voice.isRecording)
        scope.advanceTimeBy(350)
        val recording = voice.state as VoiceState.Recording
        assertEquals(3, recording.levels.size)
        assertEquals(0.5f, recording.levels.last())

        voice.stop()
        assertEquals(VoiceState.Transcribing, voice.state)
        scope.runCurrent()

        assertEquals(listOf("note-1" to "hello world"), transcripts)
        assertEquals(VoiceState.Idle, voice.state)
        assertFalse("clip is deleted after success", transcriber.sent.single().exists())
    }

    @Test
    fun `waveform keeps only the most recent samples`() {
        voice.start("n")
        scope.advanceTimeBy(100L * (VoiceController.MAX_LEVELS + 10) + 1)
        assertEquals(VoiceController.MAX_LEVELS, (voice.state as VoiceState.Recording).levels.size)
    }

    @Test
    fun `missing key asks for settings and never opens the mic`() {
        config = null
        voice.start("n")
        val error = voice.state as VoiceState.Error
        assertTrue(error.needsSettings)
        assertEquals(null, recorder.recording)
    }

    @Test
    fun `mic failure is reported`() {
        recorder.failStart = true
        voice.start("n")
        assertTrue((voice.state as VoiceState.Error).message.contains("microphone"))
    }

    @Test
    fun `failed upload keeps the audio and retry sends the same clip`() {
        transcriber.reply = { throw TranscriptionException("offline") }
        voice.start("n")
        scope.advanceTimeBy(200)
        voice.stop()
        scope.runCurrent()
        val error = voice.state as VoiceState.Error
        assertTrue(error.canRetry)
        val clip = transcriber.sent.single()
        assertTrue(clip.exists())

        transcriber.reply = { "second try" }
        voice.retry()
        scope.runCurrent()
        assertEquals(listOf(clip, clip), transcriber.sent)
        assertEquals(listOf("n" to "second try"), transcripts)
    }

    @Test
    fun `dismissing an error deletes the kept audio`() {
        transcriber.reply = { throw TranscriptionException("offline") }
        voice.start("n")
        scope.advanceTimeBy(200)
        voice.stop()
        scope.runCurrent()
        val clip = transcriber.sent.single()
        voice.dismiss()
        assertEquals(VoiceState.Idle, voice.state)
        assertFalse(clip.exists())
    }

    @Test
    fun `unusable clip is dropped without an upload`() {
        recorder.usable = false
        voice.start("n")
        voice.stop()
        scope.runCurrent()
        assertEquals(VoiceState.Idle, voice.state)
        assertTrue(transcriber.sent.isEmpty())
    }

    @Test
    fun `recording stops itself at the time limit`() {
        voice.start("n")
        scope.advanceTimeBy(10_050)
        scope.runCurrent()
        assertEquals(1, transcripts.size)
    }

    @Test
    fun `blank transcript adds nothing`() {
        transcriber.reply = { "   " }
        voice.start("n")
        voice.stop()
        scope.runCurrent()
        assertTrue(transcripts.isEmpty())
        assertEquals(VoiceState.Idle, voice.state)
    }

    @Test
    fun `toggle starts and stops, and start is ignored while busy`() {
        voice.toggle("a")
        voice.start("b") // ignored: already recording
        voice.toggle("a")
        scope.runCurrent()
        assertEquals(listOf("a" to "hello world"), transcripts)
    }
}
