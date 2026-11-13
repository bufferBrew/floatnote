package com.bufferbrew.perch.voice

import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import com.bufferbrew.perch.R

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

        /** The default service turned the language down: listen again with these options. */
        data class Retry(val options: ListenOptions) : Action

        data class Fail(@StringRes val message: Int, val code: Int? = null) : Action
    }

    /** What we ask the default service for: [language] blank means the phone's language. */
    data class ListenOptions(val language: String, val preferOffline: Boolean)

    /**
     * [relaxed] is what to try next if the default service turns the language down (from
     * [relaxLanguage]); null when there's nothing left to try.
     */
    fun onError(
        error: Int,
        usingOnDevice: Boolean,
        stopping: Boolean,
        reconnects: Int,
        relaxed: ListenOptions? = null,
    ): Action = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Action.KeepListening
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> when {
            usingOnDevice -> Action.UseDefaultService
            stopping -> Action.Finish
            relaxed != null -> Action.Retry(relaxed)
            else -> Action.Fail(R.string.err_speech_language)
        }
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

    /**
     * The default service rejected the language before hearing anything. Most often that's
     * offline mode with no offline pack for the language, so first ask again online. Then, if
     * Settings has a bare code such as "en" and the phone has a full tag for that language
     * ("en-GB"), use that tag, which some services need. Null once both have been tried.
     */
    fun relaxLanguage(current: ListenOptions, phoneTags: List<String>): ListenOptions? {
        if (current.preferOffline) return current.copy(preferOffline = false)
        val language = current.language.trim()
        if (language.isEmpty() || '-' in language || '_' in language) return null
        val tag = phoneTags.firstOrNull { it.substringBefore('-').equals(language, ignoreCase = true) && '-' in it }
            ?: return null
        return ListenOptions(tag, preferOffline = false)
    }

    /** The watchdog found the service silent for too long. */
    fun onSilentService(stopping: Boolean, reconnects: Int): Action = when {
        stopping -> Action.Finish
        reconnects >= MAX_RECONNECTS -> Action.Fail(R.string.err_speech_silent)
        else -> Action.Reconnect
    }

    /** How a segmented session ended, for [segmentsUnsupported]. */
    sealed interface SessionEnd {
        /** onResults arrived: a one-sentence answer, the way a non-segmented session ends. */
        data object Results : SessionEnd

        data class Error(val code: Int) : SessionEnd
    }

    /**
     * A segmented session (Android 13+) ended: true if that shows the service doesn't do
     * segments, so the rest of the dictation goes back to restarting after every sentence.
     * Services that ignore the request answer one sentence with onResults, or fail outright,
     * before any segment. Silence (no match, timeout) and errors after stop prove nothing.
     */
    fun segmentsUnsupported(end: SessionEnd, segmentsSoFar: Int, stopping: Boolean): Boolean {
        if (segmentsSoFar > 0) return false
        return when (end) {
            SessionEnd.Results -> true
            is SessionEnd.Error -> !stopping && end.code != SpeechRecognizer.ERROR_NO_MATCH &&
                end.code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        }
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
