package com.bufferbrew.perch.voice

import android.speech.SpeechRecognizer
import com.bufferbrew.perch.R
import com.bufferbrew.perch.voice.RecognizerPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a rejected language is retried with relaxed options before failing`() {
        val lang = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
        val online = RecognizerPolicy.ListenOptions("en", preferOffline = false)
        assertEquals(Action.Retry(online), RecognizerPolicy.onError(lang, false, false, 0, relaxed = online))
        assertEquals(Action.Finish, RecognizerPolicy.onError(lang, false, stopping = true, reconnects = 0, relaxed = online))
    }

    @Test
    fun `relaxing a language goes online first, then to the phone's full tag, then gives up`() {
        val phone = listOf("de-DE", "en-GB")
        val offline = RecognizerPolicy.ListenOptions("en", preferOffline = true)
        val online = RecognizerPolicy.relaxLanguage(offline, phone)
        assertEquals(RecognizerPolicy.ListenOptions("en", preferOffline = false), online)
        val tagged = RecognizerPolicy.relaxLanguage(online!!, phone)
        assertEquals(RecognizerPolicy.ListenOptions("en-GB", preferOffline = false), tagged)
        assertNull(RecognizerPolicy.relaxLanguage(tagged!!, phone))
    }

    @Test
    fun `nothing to relax when online with the phone's language or no matching tag`() {
        assertNull(RecognizerPolicy.relaxLanguage(RecognizerPolicy.ListenOptions("", preferOffline = false), listOf("en-GB")))
        assertNull(RecognizerPolicy.relaxLanguage(RecognizerPolicy.ListenOptions("fr", preferOffline = false), listOf("en-GB")))
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

    @Test
    fun `a one-sentence answer to a segmented request means segments are unsupported`() {
        assertTrue(RecognizerPolicy.segmentsUnsupported(RecognizerPolicy.SessionEnd.Results, segmentsSoFar = 0, stopping = false))
        // Even on stop: the service still answered the old way.
        assertTrue(RecognizerPolicy.segmentsUnsupported(RecognizerPolicy.SessionEnd.Results, segmentsSoFar = 0, stopping = true))
    }

    @Test
    fun `segments already delivered prove support`() {
        assertFalse(RecognizerPolicy.segmentsUnsupported(RecognizerPolicy.SessionEnd.Results, segmentsSoFar = 2, stopping = false))
        assertFalse(
            RecognizerPolicy.segmentsUnsupported(
                RecognizerPolicy.SessionEnd.Error(SpeechRecognizer.ERROR_CLIENT), segmentsSoFar = 1, stopping = false,
            )
        )
    }

    @Test
    fun `a segmented session failing before any segment falls back`() {
        assertTrue(
            RecognizerPolicy.segmentsUnsupported(
                RecognizerPolicy.SessionEnd.Error(SpeechRecognizer.ERROR_CLIENT), segmentsSoFar = 0, stopping = false,
            )
        )
    }

    @Test
    fun `silence and errors after stop say nothing about segment support`() {
        listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT).forEach {
            assertFalse(RecognizerPolicy.segmentsUnsupported(RecognizerPolicy.SessionEnd.Error(it), 0, stopping = false))
        }
        assertFalse(
            RecognizerPolicy.segmentsUnsupported(
                RecognizerPolicy.SessionEnd.Error(SpeechRecognizer.ERROR_CLIENT), segmentsSoFar = 0, stopping = true,
            )
        )
    }
}
