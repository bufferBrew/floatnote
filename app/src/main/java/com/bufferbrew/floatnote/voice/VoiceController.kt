package com.bufferbrew.floatnote.voice

import androidx.compose.runtime.getValue
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.util.Strings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

sealed interface VoiceState {
    data object Idle : VoiceState

    /**
     * [levels] holds recent loudness samples, oldest first, for the waveform. [partial] is the
     * sentence being heard right now (device engine only; Whisper has no live text).
     */
    data class Recording(val levels: List<Float>, val elapsedMs: Long, val partial: String = "") : VoiceState

    data object Transcribing : VoiceState

    /** [canRetry]: the audio was kept and can be sent again. [needsSettings]: fix in Settings. */
    data class Error(
        val message: String,
        val canRetry: Boolean = false,
        val needsSettings: Boolean = false,
    ) : VoiceState
}

/**
 * Speech → text for the note that was open when recording started, via [onTranscript]. One
 * instance per process (in FloatNoteApp), shared by the full app and the overlay, so the
 * bubble and the editor show the same state.
 *
 * Two engines, picked by [engine] at each start:
 * - [VoiceEngine.Whisper]: record a clip, upload it on stop. A failed upload keeps its audio
 *   file so [retry] doesn't need a new recording.
 * - [VoiceEngine.Device]: Android's recognizer; each finished sentence goes into the note as
 *   you speak, and stop just flushes the last one.
 */
class VoiceController(
    private val strings: Strings,
    private val recorder: AudioRecorder,
    private val transcriber: Transcriber,
    private val liveRecognizer: LiveRecognizer,
    private val engine: () -> VoiceEngine,
    private val config: () -> WhisperConfig?,
    /** Why [config] is null, for the error shown; null falls back to "add your key". */
    private val configProblem: () -> String? = { null },
    private val language: () -> String,
    /** Vocabulary words for the device recognizer to listen for. */
    private val hints: () -> List<String> = { emptyList() },
    private val audioDir: File,
    private val scope: CoroutineScope,
    /** [continues]: another sentence of the same live dictation, to join onto the previous one. */
    private val onTranscript: (noteId: String, text: String, continues: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** ~10 waveform updates a second: smooth enough, and cheap to draw in the overlay. */
    private val sampleIntervalMs: Long = 100,
    private val maxDurationMs: Long = 10 * 60 * 1000,
    /** How long stop waits for the device recognizer's last sentence before giving up. */
    private val finishTimeoutMs: Long = 3_000,
    /**
     * Recording stops by itself after this long without speech, so a note left open with
     * auto-record on doesn't record (and upload, and drain the battery) for 10 minutes.
     */
    private val silenceTimeoutMs: Long = 45_000,
) {
    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    val isRecording: Boolean get() = state is VoiceState.Recording

    /**
     * A note auto-record must leave alone until a different note is opened: set when another
     * app (not the user) opened it, so auto-record can't let any app switch the microphone on.
     * Tapping Record still works.
     */
    var autoRecordBlockedFor: String? = null

    private var sampler: Job? = null
    private var recordingFile: File? = null
    private var recordingNote: String? = null
    private var startedAt = 0L

    /** Last time the level was above [quietBelow]; and whether that has happened at all. */
    private var lastSoundAt = 0L
    private var heardSound = false

    /** Audio kept after a failed upload, and the note it belongs to. */
    private var pending: Pair<File, String>? = null

    /** Set while the device engine is the one running. */
    private var liveNote: String? = null
    private var liveLevel = 0f
    /** Sentences delivered so far in this live dictation. */
    private var liveSegments = 0
    private var finishTimeout: Job? = null

    /** The upload in flight, and what to keep if it's cancelled. */
    private var upload: Job? = null
    private var uploading: Pair<File, String>? = null

    init {
        restoreKept()
    }

    /**
     * A clip a previous process left behind (killed while uploading, or before Retry) is offered
     * for Retry again instead of sitting in the cache forever. Only the newest is kept.
     */
    private fun restoreKept() {
        val clips = audioDir.listFiles().orEmpty().filter { it.name.startsWith(CLIP_PREFIX) }
        val newest = clips.mapNotNull { file -> CLIP_NAME.matchEntire(file.name)?.let { file to it } }
            .maxByOrNull { (_, match) -> match.groupValues[1].toLong() }
        clips.filter { it != newest?.first }.forEach { it.delete() }
        if (newest == null) return
        pending = newest.first to newest.second.groupValues[2]
        state = VoiceState.Error(strings.get(R.string.err_kept_from_before), canRetry = true)
    }

    fun start(noteId: String) {
        if (state is VoiceState.Recording || state is VoiceState.Transcribing) return
        if (engine() == VoiceEngine.Device) {
            startLive(noteId)
            return
        }
        if (config() == null) {
            state = VoiceState.Error(
                configProblem() ?: strings.get(R.string.err_no_key_or_device),
                needsSettings = true,
            )
            return
        }
        discardPending()
        // The note id in the name lets a later process offer the clip for Retry (restoreKept).
        val file = File(audioDir, "$CLIP_PREFIX${clock()}-$noteId.m4a")
        try {
            recorder.start(file)
        } catch (e: Exception) {
            file.delete()
            state = VoiceState.Error(strings.get(R.string.err_mic_busy))
            return
        }
        recordingFile = file
        recordingNote = noteId
        startedAt = clock()
        state = VoiceState.Recording(emptyList(), 0)
        startSampler(RECORDER_QUIET) { recorder.level() }
    }

    /**
     * Ticks the timer and waveform; [level] reads the current loudness. Levels under [quietBelow]
     * count as silence (the two engines report on different scales).
     */
    private fun startSampler(quietBelow: Float, level: () -> Float) {
        lastSoundAt = clock()
        heardSound = false
        sampler = scope.launch {
            while (true) {
                delay(sampleIntervalMs)
                val current = state as? VoiceState.Recording ?: break
                val now = clock()
                val sample = level()
                if (sample >= quietBelow) {
                    lastSoundAt = now
                    heardSound = true
                }
                val elapsed = now - startedAt
                state = current.copy(
                    levels = (current.levels + sample).takeLast(MAX_LEVELS),
                    elapsedMs = elapsed,
                )
                if (elapsed >= maxDurationMs) {
                    stop()
                    break
                }
                if (now - lastSoundAt >= silenceTimeoutMs) {
                    // Nothing was said at all: don't upload a silent clip, just stop.
                    if (!heardSound && liveNote == null) cancel() else stop()
                    break
                }
            }
        }
    }

    private fun startLive(noteId: String) {
        discardPending()
        liveNote = noteId
        liveLevel = 0f
        liveSegments = 0
        startedAt = clock()
        state = VoiceState.Recording(emptyList(), 0)
        try {
            liveRecognizer.start(language(), hints(), liveListener)
        } catch (e: IllegalStateException) {
            liveNote = null
            state = VoiceState.Error(
                strings.get(R.string.err_no_recognizer),
                needsSettings = true,
            )
            return
        }
        startSampler(LIVE_QUIET) { liveLevel }
    }

    private val liveListener = object : LiveRecognizer.Listener {
        override fun onLevel(level: Float) {
            liveLevel = level
        }

        override fun onPartial(text: String) {
            // Words arriving is speech, whatever the level says.
            lastSoundAt = clock()
            heardSound = true
            (state as? VoiceState.Recording)?.let { state = it.copy(partial = text) }
        }

        override fun onSegment(text: String) {
            liveNote?.let { onTranscript(it, text, liveSegments++ > 0) }
            (state as? VoiceState.Recording)?.let { state = it.copy(partial = "") }
        }

        override fun onEnd() = endLive(VoiceState.Idle)

        override fun onError(message: String) = endLive(VoiceState.Error(message))
    }

    private fun endLive(next: VoiceState) {
        if (liveNote == null) return
        sampler?.cancel()
        sampler = null
        finishTimeout?.cancel()
        finishTimeout = null
        liveNote = null
        state = next
    }

    /** Stops recording: Whisper sends the clip off; the device engine flushes its last sentence. */
    fun stop() {
        if (state !is VoiceState.Recording) return
        sampler?.cancel()
        sampler = null
        if (liveNote != null) {
            state = VoiceState.Transcribing
            liveRecognizer.stop()
            // Normally onEnd arrives within a second; don't hang if the recognizer never answers.
            if (liveNote != null) {
                finishTimeout = scope.launch {
                    delay(finishTimeoutMs)
                    liveRecognizer.cancel()
                    endLive(VoiceState.Idle)
                }
            }
            return
        }
        val file = recordingFile
        val noteId = recordingNote
        recordingFile = null
        recordingNote = null
        val usable = recorder.stop()
        if (file == null || noteId == null || !usable) {
            file?.delete()
            state = VoiceState.Idle
            return
        }
        transcribe(file, noteId)
    }

    fun toggle(noteId: String) {
        if (state is VoiceState.Recording) stop() else start(noteId)
    }

    fun retry() {
        val (file, noteId) = pending ?: return
        pending = null
        transcribe(file, noteId)
    }

    /**
     * Gives up on whatever is in progress so the UI is never stuck waiting. A cancelled upload
     * keeps its audio for [retry]; the device engine just ends (its sentences are already saved).
     */
    fun cancel() {
        when {
            liveNote != null -> {
                liveRecognizer.cancel()
                endLive(VoiceState.Idle)
            }
            state is VoiceState.Transcribing -> {
                upload?.cancel()
                upload = null
                pending = uploading
                uploading = null
                state = if (pending != null) {
                    VoiceState.Error(strings.get(R.string.err_cancelled_retry), canRetry = true)
                } else {
                    VoiceState.Idle
                }
            }
            state is VoiceState.Recording -> {
                sampler?.cancel()
                sampler = null
                recorder.stop()
                recordingFile?.delete()
                recordingFile = null
                recordingNote = null
                state = VoiceState.Idle
            }
        }
    }

    /** Clears an error. A kept recording is thrown away. */
    fun dismiss() {
        if (state !is VoiceState.Error) return
        discardPending()
        state = VoiceState.Idle
    }

    private fun transcribe(file: File, noteId: String) {
        val cfg = config()
        if (cfg == null) {
            pending = file to noteId
            state = VoiceState.Error(
                strings.get(R.string.err_then_retry, configProblem() ?: strings.get(R.string.err_no_key)),
                canRetry = true,
                needsSettings = true,
            )
            return
        }
        state = VoiceState.Transcribing
        uploading = file to noteId
        upload = scope.launch {
            try {
                val text = transcriber.transcribe(file, cfg)
                file.delete()
                if (text.isNotBlank()) onTranscript(noteId, text, false)
                state = VoiceState.Idle
            } catch (e: TranscriptionException) {
                pending = file to noteId
                state = VoiceState.Error(e.message ?: strings.get(R.string.err_transcription_failed), canRetry = true)
            } finally {
                uploading = null
                upload = null
            }
        }
    }

    private fun discardPending() {
        pending?.first?.delete()
        pending = null
    }

    companion object {
        private const val CLIP_PREFIX = "clip-"

        /** clip-<time>-<noteId>.m4a */
        private val CLIP_NAME = Regex("""^clip-(\d+)-(.+)\.m4a$""")

        /** Samples kept for the waveform; ~2.4 s at the default rate. */
        const val MAX_LEVELS = 24

        /** MediaRecorder amplitude (0–1): a quiet room sits around 0.01, speech 0.1 and up. */
        private const val RECORDER_QUIET = 0.05f

        /** Device recognizer level from its RMS dB: silence reads up to ~0.2. */
        private const val LIVE_QUIET = 0.3f
    }
}
