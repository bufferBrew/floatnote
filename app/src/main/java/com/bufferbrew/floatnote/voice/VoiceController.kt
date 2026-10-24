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

    /** [levels] holds recent loudness samples, oldest first, for the waveform. */
    data class Recording(val levels: List<Float>, val elapsedMs: Long) : VoiceState

    data object Transcribing : VoiceState

    /** [canRetry]: the audio was kept and can be sent again. [needsSettings]: fix in Settings. */
    data class Error(
        val message: String,
        val canRetry: Boolean = false,
        val needsSettings: Boolean = false,
    ) : VoiceState
}

/**
 * Record → transcribe → hand the text to [onTranscript] for the note that was open when
 * recording started. One instance per process (in FloatNoteApp), shared by the full app and
 * the overlay, so the bubble and the editor show the same state.
 *
 * A failed upload keeps its audio file so [retry] doesn't need a new recording.
 */
class VoiceController(
    private val recorder: AudioRecorder,
    private val transcriber: Transcriber,
    private val config: () -> WhisperConfig?,
    private val audioDir: File,
    private val scope: CoroutineScope,
    private val onTranscript: (noteId: String, text: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleIntervalMs: Long = 80,
    private val maxDurationMs: Long = 10 * 60 * 1000,
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

    fun start(noteId: String) {
        if (state is VoiceState.Recording || state is VoiceState.Transcribing) return
        if (config() == null) {
            state = VoiceState.Error("Add your Whisper API key in Settings to use voice.", needsSettings = true)
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
        sampler = scope.launch {
            while (true) {
                delay(sampleIntervalMs)
                val current = state as? VoiceState.Recording ?: break
                val elapsed = clock() - startedAt
                state = current.copy(
                    levels = (current.levels + recorder.level()).takeLast(MAX_LEVELS),
                    elapsedMs = elapsed,
                )
                if (elapsed >= maxDurationMs) {
                    stop()
                    break
                }
            }
        }
    }

    /** Stops recording and sends the clip off. No-op unless recording. */
    fun stop() {
        if (state !is VoiceState.Recording) return
        sampler?.cancel()
        sampler = null
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
        scope.launch {
            try {
                val text = transcriber.transcribe(file, cfg)
                file.delete()
                if (text.isNotBlank()) onTranscript(noteId, text)
                state = VoiceState.Idle
            } catch (e: TranscriptionException) {
                pending = file to noteId
                state = VoiceState.Error(e.message ?: "Transcription failed.", canRetry = true)
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
