package com.bufferbrew.floatnote.voice

import android.speech.SpeechRecognizer
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.voice.RecognizerPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizerPolicyTest {

    @Test
    fun `silence and no-match keep dictation going`() {
        assertEquals(Action.KeepListening, RecognizerPolicy.onError(SpeechRecognizer.ERROR_NO_MATCH, false, false, 0))
        assertEquals(Action.KeepListening, RecognizerPolicy.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, true, false, 0))
    }

    @Test
    fun `unsupported language falls back from on-device, else fails`() {
        val lang = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        assertEquals(Action.UseDefaultService, RecognizerPolicy.onError(lang, usingOnDevice = true, stopping = false, reconnects = 0))
        assertEquals(Action.Fail(R.string.err_speech_language), RecognizerPolicy.onError(lang, false, false, 0))
    }

    @Test
    fun `disconnects reconnect up to the cap, and finish when stopping`() {
        val drop = SpeechRecognizer.ERROR_SERVER_DISCONNECTED
        assertEquals(Action.Reconnect, RecognizerPolicy.onError(drop, false, false, RecognizerPolicy.MAX_RECONNECTS - 1))
        assertEquals(
            Action.Fail(R.string.err_speech_disconnects),
            RecognizerPolicy.onError(drop, false, false, RecognizerPolicy.MAX_RECONNECTS),
        )
        assertEquals(Action.Finish, RecognizerPolicy.onError(SpeechRecognizer.ERROR_CLIENT, false, true, 0))
    }

    @Test
    fun `unknown errors fail with their code`() {
        assertEquals(Action.Fail(R.string.err_speech_other, 99), RecognizerPolicy.onError(99, false, false, 0))
        assertEquals(Action.Fail(R.string.err_speech_network), RecognizerPolicy.onError(SpeechRecognizer.ERROR_NETWORK, false, false, 0))
    }

    @Test
    fun `watchdog reconnects, gives up at the cap, finishes when stopping`() {
        assertEquals(Action.Reconnect, RecognizerPolicy.onSilentService(stopping = false, reconnects = 0))
        assertEquals(Action.Fail(R.string.err_speech_silent), RecognizerPolicy.onSilentService(false, RecognizerPolicy.MAX_RECONNECTS))
        assertEquals(Action.Finish, RecognizerPolicy.onSilentService(true, 0))
    }

    @Test
    fun `switch recognizer after repeated empty results that followed speech`() {
        assertEquals(0 to false, RecognizerPolicy.afterEmptyResult(heardSpeech = false, emptyAfterSpeech = 0))
        assertEquals(1 to false, RecognizerPolicy.afterEmptyResult(true, 0))
        assertEquals(0 to true, RecognizerPolicy.afterEmptyResult(true, RecognizerPolicy.EMPTY_SWITCH - 1))
    }
}
