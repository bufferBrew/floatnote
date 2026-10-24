package com.bufferbrew.floatnote.voice

import androidx.compose.runtime.getValue
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
    private val recorder: AudioRecorder,
    private val transcriber: Transcriber,
    private val liveRecognizer: LiveRecognizer,
    private val engine: () -> VoiceEngine,
    private val config: () -> WhisperConfig?,
    private val language: () -> String,
    private val audioDir: File,
    private val scope: CoroutineScope,
    private val onTranscript: (noteId: String, text: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleIntervalMs: Long = 80,
    private val maxDurationMs: Long = 10 * 60 * 1000,
    /** How long stop waits for the device recognizer's last sentence before giving up. */
    private val finishTimeoutMs: Long = 3_000,
) {
    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    val isRecording: Boolean get() = state is VoiceState.Recording

    private var sampler: Job? = null
    private var recordingFile: File? = null
    private var recordingNote: String? = null
    private var startedAt = 0L

    /** Audio kept after a failed upload, and the note it belongs to. */
    private var pending: Pair<File, String>? = null

    /** Set while the device engine is the one running. */
    private var liveNote: String? = null
    private var liveLevel = 0f
    private var finishTimeout: Job? = null

    /** The upload in flight, and what to keep if it's cancelled. */
    private var upload: Job? = null
    private var uploading: Pair<File, String>? = null

    fun start(noteId: String) {
        if (state is VoiceState.Recording || state is VoiceState.Transcribing) return
        if (engine() == VoiceEngine.Device) {
            startLive(noteId)
            return
        }
        if (config() == null) {
            state = VoiceState.Error(
                "Add your Whisper API key in Settings, or switch to device speech recognition.",
                needsSettings = true,
            )
            return
        }
        discardPending()
        val file = File(audioDir, "clip-${clock()}.m4a")
        try {
            recorder.start(file)
        } catch (e: Exception) {
            file.delete()
            state = VoiceState.Error("Couldn't start the microphone. Is another app using it?")
            return
        }
        recordingFile = file
        recordingNote = noteId
        startedAt = clock()
        state = VoiceState.Recording(emptyList(), 0)
        startSampler { recorder.level() }
    }

    /** Ticks the timer and waveform; [level] reads the current loudness. */
    private fun startSampler(level: () -> Float) {
        sampler = scope.launch {
            while (true) {
                delay(sampleIntervalMs)
                val current = state as? VoiceState.Recording ?: break
                val elapsed = clock() - startedAt
                state = current.copy(
                    levels = (current.levels + level()).takeLast(MAX_LEVELS),
                    elapsedMs = elapsed,
                )
                if (elapsed >= maxDurationMs) {
                    stop()
                    break
                }
            }
        }
    }

    private fun startLive(noteId: String) {
        discardPending()
        liveNote = noteId
        liveLevel = 0f
        startedAt = clock()
        state = VoiceState.Recording(emptyList(), 0)
        try {
            liveRecognizer.start(language(), liveListener)
        } catch (e: IllegalStateException) {
            liveNote = null
            state = VoiceState.Error(
                "This phone has no speech recognizer. Switch to Whisper in Settings.",
                needsSettings = true,
            )
            return
        }
        startSampler { liveLevel }
    }

    private val liveListener = object : LiveRecognizer.Listener {
        override fun onLevel(level: Float) {
            liveLevel = level
        }

        override fun onPartial(text: String) {
            (state as? VoiceState.Recording)?.let { state = it.copy(partial = text) }
        }

        override fun onSegment(text: String) {
            liveNote?.let { onTranscript(it, text) }
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
                    VoiceState.Error("Transcription cancelled. Retry to send the same recording.", canRetry = true)
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
                "Add your Whisper API key in Settings, then retry.",
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
                if (text.isNotBlank()) onTranscript(noteId, text)
                state = VoiceState.Idle
            } catch (e: TranscriptionException) {
                pending = file to noteId
                state = VoiceState.Error(e.message ?: "Transcription failed.", canRetry = true)
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
        /** Samples kept for the waveform; ~2 s at the default rate. */
        const val MAX_LEVELS = 24
    }
}
