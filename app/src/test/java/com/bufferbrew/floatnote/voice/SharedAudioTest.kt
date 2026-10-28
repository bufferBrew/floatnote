package com.bufferbrew.floatnote.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedAudioTest {
    @Test
    fun `a supported extension in the name is kept`() {
        assertEquals("mp3", SharedAudio.extension("Song.MP3", "audio/mpeg"))
        assertEquals("m4a", SharedAudio.extension("Call 0712.m4a", null))
    }

    @Test
    fun `whatsapp opus voice notes are sent as ogg`() {
        assertEquals("ogg", SharedAudio.extension("PTT-20261028-WA0003.opus", "audio/ogg"))
    }

    @Test
    fun `without a usable name the type decides`() {
        assertEquals("ogg", SharedAudio.extension(null, "audio/ogg; codecs=opus".substringBefore(';')))
        assertEquals("wav", SharedAudio.extension("recording", "audio/x-wav"))
    }

    @Test
    fun `an unknown format keeps a safe extension of its own`() {
        assertEquals("amr", SharedAudio.extension("memo.amr", "audio/amr"))
        assertEquals("m4a", SharedAudio.extension("weird.a/b c", null))
        assertEquals("m4a", SharedAudio.extension(null, null))
    }

    @Test
    fun `the title is the name without its extension`() {
        assertEquals("Team sync 28 Oct", SharedAudio.title("Team_sync_28 Oct.m4a"))
        assertNull(SharedAudio.title(".ogg"))
        assertNull(SharedAudio.title(null))
    }
}
