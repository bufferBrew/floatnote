package com.bufferbrew.perch.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class NotePaperTest {

    @Test
    fun `cycling visits every colour and wraps back to the theme`() {
        val seen = generateSequence(NotePaper.Theme) { it.next() }.take(NotePaper.entries.size + 1).toList()
        assertEquals(NotePaper.entries + NotePaper.Theme, seen)
    }

    @Test
    fun `saved keys round-trip and unknown ones fall back to the theme`() {
        NotePaper.entries.forEach { assertEquals(it, NotePaper.fromKey(it.key)) }
        assertEquals(NotePaper.Theme, NotePaper.fromKey(null))
        assertEquals(NotePaper.Theme, NotePaper.fromKey("purple"))
    }
}
