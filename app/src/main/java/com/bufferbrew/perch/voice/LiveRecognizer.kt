package com.bufferbrew.perch.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.bufferbrew.perch.R
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Streaming speech-to-text: words arrive while you talk. Main thread only. */
interface LiveRecognizer {
    interface Listener {
        /** Loudness, 0f (silence) to 1f. */
        fun onLevel(level: Float)

        /** Words so far in the current sentence; replaced on every call. */
        fun onPartial(text: String)

        /** A finished sentence, ready to go into the note. */
        fun onSegment(text: String)

        /** Recognition is over (after [stop], or after a fatal error). */
        fun onEnd()

        fun onError(message: String)
    }

    /**
     * [hints] are vocabulary words to bias towards (Android 13+ recognizers that support it).
     * Throws [IllegalStateException] if this phone has no speech recognizer.
     */
    fun start(language: String, hints: List<String>, listener: Listener)

    /** Finish: the last sentence is delivered, then [Listener.onEnd]. */
    fun stop()

    /** Drop everything now; no further callbacks. */
    fun cancel()
}

/**
 * Android's [SpeechRecognizer], kept listening until [stop].
 *
 * On Android 13+ each session asks for segments (EXTRA_SEGMENTED_SESSION): one long session
 * that hands over every sentence at a pause (onSegmentResults) and keeps listening, so nothing
 * said between sentences is missed and there's no start/stop tone each time. Services that don't
 * support it end the session after one sentence instead ([RecognizerPolicy.segmentsUnsupported]);
 * from then on, and on older phones, the recognizer is restarted after every sentence (and after
 * silence timeouts) to give continuous dictation.
 *
 * Which recognizer: the phone's default recognition service first (on Samsung/Pixel that's
 * Google's dictation service, offline when a language pack is installed, since we ask for
 * EXTRA_PREFER_OFFLINE). The "on-device" recognizer API is only the fallback: on a Galaxy S25
 * Ultra it runs Google's offline engine in an ambient one-shot mode that hears speech but
 * returns empty transcripts. Whichever is in use, if it hears speech yet returns nothing
 * [RecognizerPolicy.EMPTY_SWITCH] times in a row, we switch to the other for the rest of the session.
 *
 * Restarts reuse the same [SpeechRecognizer] and are posted a moment later. Destroying and
 * recreating it straight from a callback races the recognition service's own shutdown and
 * fails with ERROR_SERVER_DISCONNECTED (seen on a Galaxy S25 Ultra with Google's offline
 * recognizer). If the service does drop, it's rebuilt, up to [RecognizerPolicy.MAX_RECONNECTS] times in a row.
 */
class AndroidLiveRecognizer(private val context: Context) : LiveRecognizer {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var reconnects = 0

    /** Last time the service said anything (levels arrive several times a second while listening). */
    private var lastHeardAt = 0L

    /**
     * A recognition service can die without calling back. If it's been silent for
     * [WATCHDOG_MS] while we expect it to be listening, rebuild it rather than sit on
     * "Listening" forever.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() - lastHeardAt > WATCHDOG_MS) {
                Log.w(TAG, "speech service went silent; reconnecting")
                when (val action = RecognizerPolicy.onSilentService(stopping, reconnects)) {
                    RecognizerPolicy.Action.Finish -> return finish()
                    is RecognizerPolicy.Action.Fail -> return fail(action.message)
                    else -> {
                        reconnects++
                        lastHeardAt = SystemClock.elapsedRealtime()
                        rebuild()
                        startListening()
                    }
                }
            }
            handler.postDelayed(this, WATCHDOG_CHECK_MS)
        }
    }
    private var listener: LiveRecognizer.Listener? = null
    private var language = ""
    private var preferOffline = true
    private var hints = ArrayList<String>()
    private var active = false
    private var stopping = false
    private var usingOnDevice = false

    /** Speech was heard in the current session (onBeginningOfSpeech). */
    private var heardSpeech = false

    /** Sessions in a row that heard speech but produced no text. */
    private var emptyAfterSpeech = 0

    /**
     * The newest partial result of the current session. Some recognizers end a session with an
     * empty final result or an error (no match, timeout, disconnect) after already showing words;
     * those words are delivered from here instead of being lost.
     */
    private var lastPartial = ""

    /** The current session asked for segments. */
    private var segmented = false

    /** Segments the current session has delivered (proof the service supports them). */
    private var segmentsThisSession = 0

    /** Services seen ignoring segmented sessions, remembered for the life of the process. */
    private var segmentsUnsupportedDefault = false
    private var segmentsUnsupportedOnDevice = false

    /**
     * A session is running. Its first end (results, error or end of segments) is acted on; any
     * later one for the same session is ignored, so it can't start a second session (which the
     * service would turn down as busy).
     */
    private var sessionOpen = false

    override fun start(language: String, hints: List<String>, listener: LiveRecognizer.Listener) {
        cancel()
        this.language = language
        preferOffline = true
        this.hints = ArrayList(hints)
        this.listener = listener
        active = true
        stopping = false
        reconnects = 0
        emptyAfterSpeech = 0
        val defaultAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!defaultAvailable && !onDeviceAvailable()) {
            active = false
            throw IllegalStateException("No speech recognizer on this phone")
        }
        usingOnDevice = !defaultAvailable
        lastHeardAt = SystemClock.elapsedRealtime()
        listen()
        handler.postDelayed(watchdog, WATCHDOG_CHECK_MS)
    }

    override fun stop() {
        if (!active) return
        stopping = true
        recognizer?.stopListening() ?: finish()
    }

    override fun cancel() {
        active = false
        stopping = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Throws away the current recognizer and makes a fresh one (first start, engine switch, drop). */
    private fun rebuild() {
        recognizer?.destroy()
        val r = if (usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(callbacks)
        recognizer = r
    }

    private fun listen() {
        if (recognizer == null) rebuild()
        startListening()
    }

    /** Starts another session on the same recognizer once the current callback has returned. */
    private fun restartSoon(rebuildFirst: Boolean = false) {
        handler.postDelayed({
            if (!active) return@postDelayed
            if (stopping) {
                finish()
                return@postDelayed
            }
            if (rebuildFirst || recognizer == null) rebuild()
            startListening()
        }, RESTART_DELAY_MS)
    }

    /** The current recognizer keeps hearing speech but returning nothing: try the other one. */
    private fun noteEmptyResult() {
        val (count, switch) = RecognizerPolicy.afterEmptyResult(heardSpeech, emptyAfterSpeech)
        emptyAfterSpeech = count
        if (!switch) return
        val other = !usingOnDevice
        val otherAvailable = if (other) onDeviceAvailable() else SpeechRecognizer.isRecognitionAvailable(context)
        if (!otherAvailable) return
        Log.w(TAG, "recognizer returns empty text after speech; switching (onDevice=$other)")
        usingOnDevice = other
        emptyAfterSpeech = 0
        recognizer?.destroy()
        recognizer = null
    }

    private fun segmentsUnsupported(): Boolean =
        if (usingOnDevice) segmentsUnsupportedOnDevice else segmentsUnsupportedDefault

    /** The service in use answered a segmented request the old way: stop asking it for segments. */
    private fun noteSessionEnd(end: RecognizerPolicy.SessionEnd) {
        if (!segmented || !RecognizerPolicy.segmentsUnsupported(end, segmentsThisSession, stopping)) return
        Log.i(TAG, "segmented sessions not supported (onDevice=$usingOnDevice, $end); restarting per sentence")
        if (usingOnDevice) segmentsUnsupportedOnDevice = true else segmentsUnsupportedDefault = true
    }

    private fun startListening() {
        heardSpeech = false
        lastPartial = ""
        segmented = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !segmentsUnsupported()
        segmentsThisSession = 0
        sessionOpen = true
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                .apply { if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }
                .apply {
                    if (hints.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, hints)
                    }
                }
                .apply {
                    if (segmented && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Segments for SEGMENTED_SESSION_MS, then onEndOfSegmentedSession and a new
                        // session; stop() ends it sooner with the last segment.
                        putExtra(
                            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        )
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SEGMENTED_SESSION_MS)
                    }
                }
        )
    }

    private fun finish() {
        val l = listener
        cancel()
        l?.onEnd()
    }

    /** After a sentence or a silence timeout: keep going unless the user stopped. */
    private fun continueOrFinish() {
        if (active && !stopping) restartSoon() else finish()
    }

    private val callbacks = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            lastHeardAt = SystemClock.elapsedRealtime()
            // Typical range is about -2 dB (silence) to 10 dB (loud speech).
            listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            lastHeardAt = SystemClock.elapsedRealtime()
            best(partialResults)?.let {
                if (it.isNotBlank()) lastPartial = it.trim()
                listener?.onPartial(it)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!sessionOpen) return
            sessionOpen = false
            noteSessionEnd(RecognizerPolicy.SessionEnd.Results)
            lastHeardAt = SystemClock.elapsedRealtime()
            reconnects = 0
            val text = best(results)?.trim().orEmpty().ifEmpty { lastPartial }
            lastPartial = ""
            if (text.isNotEmpty()) {
                emptyAfterSpeech = 0
                listener?.onSegment(text)
            } else {
                noteEmptyResult()
            }
            continueOrFinish()
        }

        override fun onError(error: Int) {
            if (!sessionOpen) return
            sessionOpen = false
            noteSessionEnd(RecognizerPolicy.SessionEnd.Error(error))
            lastHeardAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "recognizer error $error (onDevice=$usingOnDevice, stopping=$stopping)")
            // Words already shown for this session go into the note whatever happens next.
            val heard = lastPartial
            lastPartial = ""
            if (heard.isNotEmpty()) {
                emptyAfterSpeech = 0
                listener?.onSegment(heard)
            }
            val relaxed = RecognizerPolicy.relaxLanguage(
                RecognizerPolicy.ListenOptions(language, preferOffline),
                LocaleList.getDefault().toLanguageTags().split(','),
            )
            when (val action = RecognizerPolicy.onError(error, usingOnDevice, stopping, reconnects, relaxed)) {
                RecognizerPolicy.Action.KeepListening -> {
                    if (heard.isEmpty()) noteEmptyResult()
                    continueOrFinish()
                }
                RecognizerPolicy.Action.UseDefaultService -> {
                    usingOnDevice = false
                    if (active && !stopping) restartSoon(rebuildFirst = true) else finish()
                }
                is RecognizerPolicy.Action.Retry -> {
                    Log.w(TAG, "language rejected; retrying with ${action.options}")
                    language = action.options.language
                    preferOffline = action.options.preferOffline
                    restartSoon()
                }
                RecognizerPolicy.Action.Reconnect -> {
                    reconnects++
                    restartSoon(rebuildFirst = true)
                }
                RecognizerPolicy.Action.Finish -> finish()
                is RecognizerPolicy.Action.Fail ->
                    if (action.code != null) fail(action.message, action.code) else fail(action.message)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {
            lastHeardAt = SystemClock.elapsedRealtime()
        }
        override fun onBeginningOfSpeech() {
            lastHeardAt = SystemClock.elapsedRealtime()
            heardSpeech = true
        }
        /** Android 13+, segmented sessions: one sentence, and the session keeps listening. */
        override fun onSegmentResults(segmentResults: Bundle) {
            if (!sessionOpen) return
            lastHeardAt = SystemClock.elapsedRealtime()
            reconnects = 0
            segmentsThisSession++
            val text = best(segmentResults)?.trim().orEmpty().ifEmpty { lastPartial }
            lastPartial = ""
            if (text.isNotEmpty()) {
                emptyAfterSpeech = 0
                listener?.onSegment(text)
            }
        }

        /** The segmented session is over (its time ran out, or stop): carry on or finish. */
        override fun onEndOfSegmentedSession() {
            if (!sessionOpen) return
            sessionOpen = false
            lastHeardAt = SystemClock.elapsedRealtime()
            val heard = lastPartial
            lastPartial = ""
            if (heard.isNotEmpty()) listener?.onSegment(heard)
            continueOrFinish()
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun fail(@StringRes id: Int, vararg args: Any) {
        val message = context.getString(id, *args)
        Log.w(TAG, "Speech recognition failed: $message")
        val l = listener
        cancel()
        l?.onError(message)
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val TAG = "PerchVoice"
        /**
         * Speech in this gap between sessions isn't heard, so keep it short. What avoids the
         * ERROR_SERVER_DISCONNECTED race is restarting after the callback has returned (posting),
         * not the length of the wait. It was 150 ms; if disconnects come back, raise it again.
         */
        const val RESTART_DELAY_MS = 50L
        const val WATCHDOG_MS = 8_000L

        /**
         * How long one segmented session runs before a fresh one starts (a single short gap).
         * An int, as RecognizerIntent documents for EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS.
         */
        const val SEGMENTED_SESSION_MS = 10 * 60 * 1000
        const val WATCHDOG_CHECK_MS = 2_000L
    }
}
