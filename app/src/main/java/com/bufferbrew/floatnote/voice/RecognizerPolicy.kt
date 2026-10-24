package com.bufferbrew.floatnote.voice

import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import com.bufferbrew.floatnote.R

/**
 * The decisions [AndroidLiveRecognizer] makes when the speech service misbehaves, as pure
 * functions so they're unit-tested. The recognizer only carries them out.
 */
internal object RecognizerPolicy {
    const val MAX_RECONNECTS = 3
    const val EMPTY_SWITCH = 2

    sealed interface Action {
        /** Silence or nothing understood: counts as an empty result, then keep listening. */
        data object KeepListening : Action

        /** The on-device recognizer has no model for this language: use the default service. */
        data object UseDefaultService : Action

        /** The service dropped: rebuild it and listen again (counts as a reconnect). */
        data object Reconnect : Action

        /** The user already pressed stop: end cleanly. */
        data object Finish : Action

        data class Fail(@StringRes val message: Int, val code: Int? = null) : Action
    }

    fun onError(error: Int, usingOnDevice: Boolean, stopping: Boolean, reconnects: Int): Action = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Action.KeepListening
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            if (usingOnDevice) Action.UseDefaultService else Action.Fail(R.string.err_speech_language)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Action.Fail(R.string.err_speech_permission)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Action.Fail(R.string.err_speech_busy)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            Action.Fail(R.string.err_speech_network)
        // The service went away under us: reconnect, but don't loop forever.
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT -> when {
            stopping -> Action.Finish
            reconnects < MAX_RECONNECTS -> Action.Reconnect
            else -> Action.Fail(R.string.err_speech_disconnects)
        }
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> Action.Fail(R.string.err_speech_rate)
        else -> Action.Fail(R.string.err_speech_other, error)
    }

    /** The watchdog found the service silent for too long. */
    fun onSilentService(stopping: Boolean, reconnects: Int): Action = when {
        stopping -> Action.Finish
        reconnects >= MAX_RECONNECTS -> Action.Fail(R.string.err_speech_silent)
        else -> Action.Reconnect
    }

    /**
     * After a session ended with no text: the new count of "heard speech but returned nothing"
     * in a row, and whether to switch to the other recognizer now.
     */
    fun afterEmptyResult(heardSpeech: Boolean, emptyAfterSpeech: Int): Pair<Int, Boolean> {
        if (!heardSpeech) return emptyAfterSpeech to false
        val count = emptyAfterSpeech + 1
        return if (count >= EMPTY_SWITCH) 0 to true else count to false
    }
}
