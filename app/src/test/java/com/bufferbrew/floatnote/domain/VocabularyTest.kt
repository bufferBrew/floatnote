package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.VocabEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyTest {

    @Test
    fun `bare word fixes its spelling, whole words only`() {
        val entries = listOf(VocabEntry(1, "Kubernetes"))
        assertEquals("Deploy to Kubernetes now", Vocabulary.apply("Deploy to kubernetes now", entries))
        assertEquals("kubernetesy", Vocabulary.apply("kubernetesy", entries))
    }

    @Test
    fun `replacement rewrites the heard phrase, case-insensitively`() {
        val entries = listOf(VocabEntry(1, "my email", "kj@example.com"))
        assertEquals("send it to kj@example.com.", Vocabulary.apply("send it to My Email.", entries))
    }

    @Test
    fun `longer terms win over shorter ones`() {
        val entries = listOf(VocabEntry(1, "New York", "NY"), VocabEntry(2, "New York Times", "NYT"))
        assertEquals("read the NYT in NY", Vocabulary.apply("read the new york times in new york", entries))
    }

    @Test
    fun `terms with symbols match`() {
        assertEquals("I write C++ daily", Vocabulary.apply("I write c++ daily", listOf(VocabEntry(1, "C++"))))
    }

    @Test
    fun `hints prefer the replacement and the prompt is bounded`() {
        val entries = listOf(VocabEntry(1, "floatnote", "FloatNote"), VocabEntry(2, "Wispr"))
        assertEquals(listOf("FloatNote", "Wispr"), Vocabulary.hints(entries))
        assertEquals("Vocabulary: FloatNote, Wispr.", Vocabulary.whisperPrompt(entries))
        assertEquals("", Vocabulary.whisperPrompt(emptyList()))
        val many = (1..500L).map { VocabEntry(it, "word$it") }
        assertTrue(Vocabulary.whisperPrompt(many).length < 650)
    }
}
