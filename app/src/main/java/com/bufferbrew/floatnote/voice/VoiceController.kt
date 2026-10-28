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
     * [canPause]: a Whisper clip, which can pause; [paused] while it is (by the user, a call or
     * an alarm). [elapsedMs] leaves out paused time.
     */
    data class Recording(
        val levels: List<Float>,
        val elapsedMs: Long,
        val partial: String = "",
        val canPause: Boolean = false,
        val paused: Boolean = false,
        /** Whisper was chosen but can't be used right now, so the phone's recognizer is listening. */
        val fallback: Boolean = false,
    ) : VoiceState

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
 *   file so [retry] doesn't need a new recording. A long clip is recorded as back-to-back files
 *   of at most [segmentBytes] (no gap between them), sent one after another, because the server
 *   refuses big uploads (25 MB on OpenAI). Without a key, or offline, it falls back to the
 *   phone's recognizer when there is one.
 * - [VoiceEngine.Device]: Android's recognizer; each finished sentence goes into the note as
 *   you speak, and stop just flushes the last one.
 *
 * A Whisper clip pauses for a call or an alarm ([interruptions]) and carries on when it ends,
 * so one clip survives the interruption. The device engine doesn't: its sentences are already
 * in the note, and the recognizer handles losing the mic itself.
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
    /**
     * [continues]: another sentence of the same dictation, to join onto the previous one. [audio]
     * is the clip it came from (null for the device engine); the receiver now owns the file and
     * keeps or deletes it.
     */
    private val onTranscript: (noteId: String, text: String, continues: Boolean, audio: File?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** ~10 waveform updates a second: smooth enough, and cheap to draw in the overlay. */
    private val sampleIntervalMs: Long = 100,
    private val maxDurationMs: Long = 2 * 60 * 60 * 1000,
    /** How long stop waits for the device recognizer's last sentence before giving up. */
    private val finishTimeoutMs: Long = 3_000,
    /**
     * Recording stops by itself after this long without speech, so a note left open with
     * auto-record on doesn't record (and upload, and drain the battery) for 10 minutes.
     */
    private val silenceTimeoutMs: Long = 45_000,
    private val interruptions: AudioInterruptions = NoInterruptions,
    /** A clip left paused this long is stopped and sent, so it can't hold the mic indefinitely. */
    private val maxPauseMs: Long = 10 * 60 * 1000,
    /** Size of each file of a long clip; ~14 minutes at the recorder's bitrate. */
    private val segmentBytes: Long = 5L * 1024 * 1024,
    /** Whether the network is up; offline, a new recording uses the phone's recognizer if it can. */
    private val online: () -> Boolean = { true },
) {
    /** An audio file to transcribe, its note, and whether it carries on from the file before it. */
    private data class Clip(val file: File, val noteId: String, val continues: Boolean = false)

    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    val isRecording: Boolean get() = state is VoiceState.Recording

    /** Recording or transcribing: a new clip or shared file has to wait. */
    val isBusy: Boolean get() = state is VoiceState.Recording || state is VoiceState.Transcribing

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

    /** When the clip was paused (null while it records), and the paused time before that. */
    private var pausedAt: Long? = null
    private var pausedTotal = 0L
    /** Paused by a call or alarm, so its end resumes; a pause the user chose stays paused. */
    private var pausedByInterruption = false

    /** Files waiting behind the one being transcribed: later parts of a long clip, or shared files. */
    private val queued = ArrayDeque<Clip>()

    /** Last time the level was above [quietBelow]; and whether that has happened at all. */
    private var lastSoundAt = 0L
    private var heardSound = false

    /** Audio kept after a failed upload, and the note it belongs to. */
    private var pending: Clip? = null

    /** Set while the device engine is the one running. */
    private var liveNote: String? = null
    private var liveLevel = 0f
    /** Sentences delivered so far in this live dictation. */
    private var liveSegments = 0
    private var finishTimeout: Job? = null

    /** The upload in flight, and what to keep if it's cancelled. */
    private var upload: Job? = null
    private var uploading: Clip? = null

    init {
        restoreKept()
    }

    /**
     * A clip a previous process left behind (killed while uploading, or before Retry) is offered
     * for Retry again instead of sitting in the cache forever. Only the newest clip's note keeps
     * its files (all parts of a long clip, or a share), oldest first.
     */
    private fun restoreKept() {
        val files = audioDir.listFiles().orEmpty().filter { it.name.startsWith(CLIP_PREFIX) }
        val clips = files.mapNotNull { file ->
            CLIP_NAME.matchEntire(file.name)?.let { Triple(file, it.groupValues[1].toLong(), it.groupValues[2]) }
        }
        val note = clips.maxByOrNull { it.second }?.third
        val kept = clips.filter { it.third == note }.sortedBy { it.second }.map { it.first }
        files.filter { it !in kept }.forEach { it.delete() }
        if (note == null || kept.isEmpty()) return
        pending = Clip(kept.first(), note)
        kept.drop(1).forEach { queued.addLast(Clip(it, note, continues = true)) }
        state = VoiceState.Error(strings.get(R.string.err_kept_from_before), canRetry = true)
    }

    fun start(noteId: String) {
        if (state is VoiceState.Recording || state is VoiceState.Transcribing) return
        if (engine() == VoiceEngine.Device) {
            startLive(noteId, fallback = false)
            return
        }
        val configured = config() != null
        // Whisper can't be used right now: the phone's recognizer still works (offline too, when
        // it has a model for the language), which beats an error.
        if (!configured || !online()) {
            if (startLive(noteId, fallback = true)) return
            if (!configured) {
                state = VoiceState.Error(
                    configProblem() ?: strings.get(R.string.err_no_key_or_device),
                    needsSettings = true,
                )
                return
            }
            // Offline with no recognizer: record anyway; the clip waits for Retry.
        }
        discardPending()
        val file = clipFile(noteId)
        try {
            recorder.start(file, segmentBytes) { clipFile(noteId) }
        } catch (e: Exception) {
            file.delete()
            state = VoiceState.Error(strings.get(R.string.err_mic_busy))
            return
        }
        recordingFile = file
        recordingNote = noteId
        startedAt = clock()
        pausedAt = null
        pausedTotal = 0
        pausedByInterruption = false
        state = VoiceState.Recording(emptyList(), 0, canPause = true)
        interruptions.watch(
            onPause = { pause(byInterruption = true) },
            onResume = { if (pausedByInterruption) resume() },
        )
        startSampler(RECORDER_QUIET) { recorder.level() }
    }

    /** The note id in the name lets a later process offer the clip for Retry (restoreKept). */
    private fun clipFile(noteId: String) = File(audioDir, "$CLIP_PREFIX${clock()}-$noteId.m4a")

    /** Pauses a Whisper clip; [resume] carries on in the same clip. */
    fun pause() = pause(byInterruption = false)

    private fun pause(byInterruption: Boolean) {
        val current = state as? VoiceState.Recording ?: return
        if (!current.canPause || current.paused) return
        // Throws if the recorder is in a bad state; the clip then just keeps recording.
        if (runCatching { recorder.pause() }.isFailure) return
        pausedAt = clock()
        pausedByInterruption = byInterruption
        state = current.copy(paused = true)
    }

    fun resume() {
        val current = state as? VoiceState.Recording ?: return
        val since = pausedAt ?: return
        if (runCatching { recorder.resume() }.isFailure) {
            // Can't carry on: send what was recorded before the pause.
            stop()
            return
        }
        val now = clock()
        pausedTotal += now - since
        pausedAt = null
        pausedByInterruption = false
        // The silence timer starts again: the pause wasn't silence.
        lastSoundAt = now
        state = current.copy(paused = false)
    }

    fun togglePause() {
        if ((state as? VoiceState.Recording)?.paused == true) resume() else pause()
    }

    /** Ends the clip's bookkeeping: the waveform timer, the focus watch and any pause. */
    private fun endClip() {
        sampler?.cancel()
        sampler = null
        interruptions.unwatch()
        pausedAt = null
        pausedByInterruption = false
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
                val paused = pausedAt
                if (paused != null) {
                    if (now - paused >= maxPauseMs) {
                        stop()
                        break
                    }
                    continue
                }
                val sample = level()
                if (sample >= quietBelow) {
                    lastSoundAt = now
                    heardSound = true
                }
                val elapsed = now - startedAt - pausedTotal
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

    /**
     * Starts the device recognizer. False if the phone has none; then [fallback] (standing in for
     * Whisper) leaves the state for the caller, and otherwise it's reported here.
     */
    private fun startLive(noteId: String, fallback: Boolean): Boolean {
        val before = state
        liveNote = noteId
        liveLevel = 0f
        liveSegments = 0
        startedAt = clock()
        state = VoiceState.Recording(emptyList(), 0, fallback = fallback)
        try {
            liveRecognizer.start(language(), hints(), liveListener)
        } catch (e: IllegalStateException) {
            liveNote = null
            state = if (fallback) {
                if (before is VoiceState.Error) VoiceState.Idle else before
            } else {
                VoiceState.Error(strings.get(R.string.err_no_recognizer), needsSettings = true)
            }
            return false
        }
        // Only now: a failed fallback must not throw away a clip kept for Retry.
        discardPending()
        startSampler(LIVE_QUIET) { liveLevel }
        return true
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
            liveNote?.let { onTranscript(it, text, liveSegments++ > 0, null) }
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
        endClip()
        val file = recordingFile
        val noteId = recordingNote
        recordingFile = null
        recordingNote = null
        val files = recorder.stop()
        if (noteId == null || files.isEmpty()) {
            file?.delete()
            state = VoiceState.Idle
            return
        }
        // Later parts of a long clip continue the first part's paragraph.
        files.drop(1).forEach { queued.addLast(Clip(it, noteId, continues = true)) }
        transcribe(Clip(files.first(), noteId))
    }

    fun toggle(noteId: String) {
        if (state is VoiceState.Recording) stop() else start(noteId)
    }

    fun retry() {
        val clip = pending ?: return
        pending = null
        transcribe(clip)
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
                endClip()
                recorder.stop().forEach { it.delete() }
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

    private fun transcribe(clip: Clip) {
        val cfg = config()
        if (cfg == null) {
            pending = clip
            state = VoiceState.Error(
                strings.get(R.string.err_then_retry, configProblem() ?: strings.get(R.string.err_no_key)),
                canRetry = true,
                needsSettings = true,
            )
            return
        }
        state = VoiceState.Transcribing
        uploading = clip
        upload = scope.launch {
            var next: Clip? = null
            try {
                val text = transcriber.transcribe(clip.file, cfg)
                if (text.isNotBlank()) onTranscript(clip.noteId, text, clip.continues, clip.file) else clip.file.delete()
                state = VoiceState.Idle
                next = queued.removeFirstOrNull()
            } catch (e: TranscriptionException) {
                // The files behind it wait: Retry sends this one, then carries on with them.
                pending = clip
                state = VoiceState.Error(e.message ?: strings.get(R.string.err_transcription_failed), canRetry = true)
            } finally {
                uploading = null
                upload = null
            }
            next?.let(::transcribe)
        }
    }

    /**
     * Where to copy an audio file shared from another app, before [transcribeFiles]. Named like a
     * recording so a process killed mid-upload offers it for Retry too ([restoreKept]).
     */
    fun sharedClipFile(noteId: String, index: Int, extension: String): File =
        File(audioDir, "$CLIP_PREFIX${clock() + index}-$noteId.$extension")

    /**
     * Transcribes audio files shared from another app into [noteId], one after another, through
     * the same upload as a recording (a failed one is kept for Retry). False, and the files are
     * deleted, while a recording or upload is already going.
     */
    fun transcribeFiles(files: List<File>, noteId: String): Boolean {
        if (files.isEmpty()) return false
        if (isBusy) {
            files.forEach { it.delete() }
            return false
        }
        discardPending()
        files.drop(1).forEach { queued.addLast(Clip(it, noteId)) }
        transcribe(Clip(files.first(), noteId))
        return true
    }

    private fun discardPending() {
        pending?.file?.delete()
        pending = null
        queued.forEach { it.file.delete() }
        queued.clear()
    }

    companion object {
        private const val CLIP_PREFIX = "clip-"

        /** clip-<time>-<noteId>.<ext>: a recording (.m4a) or a shared file. */
        private val CLIP_NAME = Regex("""^clip-(\d+)-(.+)\.\w+$""")

        /** Samples kept for the waveform; ~2.4 s at the default rate. */
        const val MAX_LEVELS = 24

        /** MediaRecorder amplitude (0–1): a quiet room sits around 0.01, speech 0.1 and up. */
        private const val RECORDER_QUIET = 0.05f

        /** Device recognizer level from its RMS dB: silence reads up to ~0.2. */
        private const val LIVE_QUIET = 0.3f
    }
}
