package com.bufferbrew.floatnote.voice

import com.bufferbrew.floatnote.util.TestStrings
import kotlinx.coroutines.CompletableDeferred
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
        /** When set, transcribe() suspends until this completes, like a slow network. */
        var gate: CompletableDeferred<Unit>? = null
        val sent = mutableListOf<File>()
        override suspend fun transcribe(audio: File, config: WhisperConfig): String {
            sent += audio
            gate?.await()
            return reply()
        }
    }

    private class FakeLive : LiveRecognizer {
        var listener: LiveRecognizer.Listener? = null
        var unavailable = false
        var stopped = false
        var cancelled = false
        /** When false, stop() never answers, to exercise the timeout. */
        var answersStop = true
        override fun start(language: String, hints: List<String>, listener: LiveRecognizer.Listener) {
            if (unavailable) throw IllegalStateException("none")
            this.listener = listener
        }
        override fun stop() {
            stopped = true
            if (answersStop) {
                listener?.onSegment("last words")
                listener?.onEnd()
            }
        }
        override fun cancel() { cancelled = true }
    }

    private val dir: File = Files.createTempDirectory("voice").toFile()
    private val scope = TestScope(StandardTestDispatcher())
    private val recorder = FakeRecorder()
    private val transcriber = FakeTranscriber()
    private val live = FakeLive()
    private var engine = VoiceEngine.Whisper
    private var config: WhisperConfig? = WhisperConfig("k", "https://example.test/v1", "whisper-1", "")
    private val transcripts = mutableListOf<Pair<String, String>>()

    private val voice = VoiceController(
        strings = TestStrings,
        recorder = recorder,
        transcriber = transcriber,
        liveRecognizer = live,
        engine = { engine },
        config = { config },
        language = { "" },
        audioDir = dir,
        scope = scope,
        onTranscript = { id, text, _ -> transcripts += id to text },
        clock = { scope.testScheduler.currentTime },
        sampleIntervalMs = 100,
        maxDurationMs = 10_000,
        finishTimeoutMs = 3_000,
    )

    private fun silenceVoice() = VoiceController(
        strings = TestStrings,
        recorder = recorder,
        transcriber = transcriber,
        liveRecognizer = live,
        engine = { engine },
        config = { config },
        language = { "" },
        audioDir = dir,
        scope = scope,
        onTranscript = { id, text, _ -> transcripts += id to text },
        clock = { scope.testScheduler.currentTime },
        sampleIntervalMs = 100,
        maxDurationMs = 60_000,
        silenceTimeoutMs = 5_000,
    )

    @Test
    fun `silence after speech stops and sends the clip`() {
        val voice = silenceVoice()
        voice.start("n")
        scope.advanceTimeBy(1_000) // speaking
        recorder.level = 0.01f
        scope.advanceTimeBy(5_200)
        scope.runCurrent()
        assertFalse(voice.isRecording)
        assertEquals(listOf("n" to "hello world"), transcripts)
    }

    @Test
    fun `a clip with no speech at all is dropped, not uploaded`() {
        recorder.level = 0.01f
        val voice = silenceVoice()
        voice.start("n")
        scope.advanceTimeBy(5_200)
        assertEquals(VoiceState.Idle, voice.state)
        assertTrue(transcriber.sent.isEmpty())
    }

    @Test
    fun `talking keeps the recording going`() {
        val voice = silenceVoice()
        voice.start("n")
        scope.advanceTimeBy(20_000)
        assertTrue(voice.isRecording)
    }

    @Test
    fun `device engine stops after silence, words count as speech`() {
        engine = VoiceEngine.Device
        val voice = silenceVoice()
        voice.start("n")
        live.listener!!.onLevel(0.05f)
        scope.advanceTimeBy(4_000)
        live.listener!!.onPartial("still talking")
        scope.advanceTimeBy(4_000)
        assertTrue(voice.isRecording)
        scope.advanceTimeBy(1_200)
        assertTrue(live.stopped)
    }

    @Test
    fun `device sentences after the first are marked as continuing`() {
        val flags = mutableListOf<Boolean>()
        val v = VoiceController(
            strings = TestStrings,
            recorder = recorder, transcriber = transcriber, liveRecognizer = live,
            engine = { VoiceEngine.Device }, config = { config }, language = { "" },
            audioDir = dir, scope = scope, onTranscript = { _, _, continues -> flags += continues },
            clock = { scope.testScheduler.currentTime },
        )
        v.start("n")
        live.listener!!.onSegment("one")
        live.listener!!.onSegment("two")
        v.stop()
        v.start("n")
        live.listener!!.onSegment("three")
        assertEquals(listOf(false, true, true, false), flags)
    }

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

    @Test
    fun `device engine streams sentences into the note as they are heard`() {
        engine = VoiceEngine.Device
        config = null // no Whisper key needed
        voice.start("n")
        assertTrue(voice.isRecording)
        assertEquals(null, recorder.recording) // the file recorder isn't used

        live.listener!!.onLevel(0.8f)
        scope.advanceTimeBy(150)
        assertEquals(0.8f, (voice.state as VoiceState.Recording).levels.last())

        live.listener!!.onPartial("buy mi")
        assertEquals("buy mi", (voice.state as VoiceState.Recording).partial)
        live.listener!!.onSegment("buy milk")
        assertEquals(listOf("n" to "buy milk"), transcripts)
        assertEquals("", (voice.state as VoiceState.Recording).partial)

        voice.stop()
        assertTrue(live.stopped)
        assertEquals(listOf("n" to "buy milk", "n" to "last words"), transcripts)
        assertEquals(VoiceState.Idle, voice.state)
        assertTrue(transcriber.sent.isEmpty())
    }

    @Test
    fun `device engine stop gives up if the recognizer never answers`() {
        engine = VoiceEngine.Device
        live.answersStop = false
        voice.start("n")
        voice.stop()
        assertEquals(VoiceState.Transcribing, voice.state)
        scope.advanceTimeBy(3_001)
        assertTrue(live.cancelled)
        assertEquals(VoiceState.Idle, voice.state)
    }

    @Test
    fun `device engine error is shown`() {
        engine = VoiceEngine.Device
        voice.start("n")
        live.listener!!.onError("busy")
        assertEquals("busy", (voice.state as VoiceState.Error).message)
        assertFalse(voice.isRecording)
    }

    @Test
    fun `no recognizer on the phone points to Settings`() {
        engine = VoiceEngine.Device
        live.unavailable = true
        voice.start("n")
        assertTrue((voice.state as VoiceState.Error).needsSettings)
    }

    @Test
    fun `device engine respects the time limit`() {
        engine = VoiceEngine.Device
        voice.start("n")
        scope.advanceTimeBy(10_050)
        assertTrue(live.stopped)
    }

    @Test
    fun `cancelling a slow upload frees the UI and keeps the audio for retry`() {
        transcriber.gate = CompletableDeferred()
        voice.start("n")
        scope.advanceTimeBy(200)
        voice.stop()
        scope.runCurrent()
        assertEquals(VoiceState.Transcribing, voice.state)

        voice.cancel()
        val error = voice.state as VoiceState.Error
        assertTrue(error.canRetry)
        val clip = transcriber.sent.single()
        assertTrue(clip.exists())

        transcriber.gate = null
        voice.retry()
        scope.runCurrent()
        assertEquals(listOf("n" to "hello world"), transcripts)
        assertEquals(listOf(clip, clip), transcriber.sent)
    }

    @Test
    fun `a new recording can start right after cancelling an upload`() {
        transcriber.gate = CompletableDeferred()
        voice.start("n")
        voice.stop()
        scope.runCurrent()
        voice.cancel()
        voice.dismiss()
        voice.start("n")
        assertTrue(voice.isRecording)
    }

    @Test
    fun `cancelling a recording drops it without uploading`() {
        voice.start("n")
        scope.advanceTimeBy(300)
        voice.cancel()
        scope.runCurrent()
        assertEquals(VoiceState.Idle, voice.state)
        assertTrue(transcriber.sent.isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancelling a device session that is finishing ends it at once`() {
        engine = VoiceEngine.Device
        live.answersStop = false
        voice.start("n")
        voice.stop()
        assertEquals(VoiceState.Transcribing, voice.state)
        voice.cancel()
        assertTrue(live.cancelled)
        assertEquals(VoiceState.Idle, voice.state)
    }

    private fun restartedVoice() = VoiceController(
        strings = TestStrings,
        recorder = recorder,
        transcriber = transcriber,
        liveRecognizer = live,
        engine = { engine },
        config = { config },
        language = { "" },
        audioDir = dir,
        scope = scope,
        onTranscript = { id, text, _ -> transcripts += id to text },
        clock = { scope.testScheduler.currentTime },
    )

    @Test
    fun `a clip left by an earlier process is offered for retry, older ones are deleted`() {
        val older = File(dir, "clip-100-note-a.m4a").apply { writeBytes(byteArrayOf(1)) }
        val newest = File(dir, "clip-200-note-b.m4a").apply { writeBytes(byteArrayOf(1)) }
        val legacy = File(dir, "clip-50.m4a").apply { writeBytes(byteArrayOf(1)) }
        val voice = restartedVoice()
        assertTrue((voice.state as VoiceState.Error).canRetry)
        assertFalse(older.exists())
        assertFalse(legacy.exists())
        voice.retry()
        scope.runCurrent()
        assertEquals(listOf(newest), transcriber.sent)
        assertEquals(listOf("note-b" to "hello world"), transcripts)
        assertFalse(newest.exists())
    }

    @Test
    fun `a new recording names its clip after the note`() {
        voice.start("note-c")
        assertTrue(recorder.recording!!.name.matches(Regex("clip-\\d+-note-c\\.m4a")))
        voice.cancel()
    }

    @Test
    fun `nothing left over means a clean start`() {
        assertEquals(VoiceState.Idle, restartedVoice().state)
    }
}
