package com.bufferbrew.floatnote.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

    /** Throws [IllegalStateException] if this phone has no speech recognizer. */
    fun start(language: String, listener: Listener)

    /** Finish: the last sentence is delivered, then [Listener.onEnd]. */
    fun stop()

    /** Drop everything now; no further callbacks. */
    fun cancel()
}

/**
 * Android's [SpeechRecognizer], kept listening until [stop].
 *
 * The platform recognizer ends each session at a pause, so this restarts it after every
 * sentence (and after silence timeouts) to give continuous dictation. It prefers the on-device
 * recognizer (offline, private); if that has no model for the language it falls back to the
 * phone's default recognition service for the rest of the session.
 */
class AndroidLiveRecognizer(private val context: Context) : LiveRecognizer {
    private var recognizer: SpeechRecognizer? = null
    private var listener: LiveRecognizer.Listener? = null
    private var language = ""
    private var active = false
    private var stopping = false
    private var usingOnDevice = false

    override fun start(language: String, listener: LiveRecognizer.Listener) {
        cancel()
        this.language = language
        this.listener = listener
        active = true
        stopping = false
        usingOnDevice = onDeviceAvailable()
        if (!usingOnDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            active = false
            throw IllegalStateException("No speech recognizer on this phone")
        }
        listen()
    }

    override fun stop() {
        if (!active) return
        stopping = true
        recognizer?.stopListening() ?: finish()
    }

    override fun cancel() {
        active = false
        stopping = false
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun listen() {
        recognizer?.destroy()
        val r = if (usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(callbacks)
        recognizer = r
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .apply { if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }
        )
    }

    private fun finish() {
        val l = listener
        cancel()
        l?.onEnd()
    }

    /** After a sentence or a silence timeout: keep going unless the user stopped. */
    private fun continueOrFinish() {
        if (active && !stopping) listen() else finish()
    }

    private val callbacks = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            // Typical range is about -2 dB (silence) to 10 dB (loud speech).
            listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { listener?.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            best(results)?.takeIf { it.isNotBlank() }?.let { listener?.onSegment(it.trim()) }
            continueOrFinish()
        }

        override fun onError(error: Int) {
            when (error) {
                // Silence or nothing understood: not a failure in dictation, just keep listening.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> continueOrFinish()
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    if (usingOnDevice) {
                        // No offline model for this language: use the phone's regular service.
                        usingOnDevice = false
                        if (active && !stopping) listen() else finish()
                    } else {
                        fail("Speech recognition doesn't support this language. Try clearing Language in Settings.")
                    }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("FloatNote needs microphone access.")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> fail("The speech recognizer is busy in another app.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    fail("Speech recognition needs a connection on this phone for this language.")
                // Raised when stopListening() lands with nothing pending; just end.
                SpeechRecognizer.ERROR_CLIENT -> if (stopping) finish() else fail("Speech recognition stopped unexpectedly.")
                else -> fail("Speech recognition failed (error $error).")
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun fail(message: String) {
        val l = listener
        cancel()
        l?.onError(message)
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
