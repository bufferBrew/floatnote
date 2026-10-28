package com.bufferbrew.floatnote.voice

import com.bufferbrew.floatnote.domain.model.VocabEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptPipelineTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val added = mutableListOf<Pair<String, String>>()
    private val history = mutableListOf<Triple<String, String, String>>()
    private val audio = mutableListOf<java.io.File?>()
    private var cleanup = false
    private var cleaner: suspend (String, List<String>) -> String = { t, _ -> t }

    private val pipeline = TranscriptPipeline(
        scope = scope,
        vocabulary = { listOf(VocabEntry(1, "FloatNote")) },
        cleanupEnabled = { cleanup },
        cleanUp = { t, v -> cleaner(t, v) },
        addToNote = { id, t, _ -> added += id to t },
        addToHistory = { id, t, raw, file -> history += Triple(id, t, raw); audio += file },
    )

    @Test
    fun `applies vocabulary and records history with what was heard`() {
        pipeline.submit("n", " try floatnote ")
        scope.runCurrent()
        assertEquals(listOf("n" to "try FloatNote"), added)
        assertEquals(listOf(Triple("n", "try FloatNote", "try floatnote")), history)
    }

    @Test
    fun `cleanup keeps arrival order even when the first is slow`() {
        cleanup = true
        val gate = CompletableDeferred<Unit>()
        cleaner = { t, _ -> if (t.startsWith("one")) gate.await(); t.uppercase() }
        pipeline.submit("n", "one um")
        pipeline.submit("n", "two")
        scope.runCurrent()
        assertEquals(emptyList<Pair<String, String>>(), added)
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("n" to "ONE UM", "n" to "TWO"), added)
    }

    @Test
    fun `failed cleanup falls back to the uncleaned text`() {
        cleanup = true
        cleaner = { _, _ -> throw IllegalStateException("offline") }
        pipeline.submit("n", "hello floatnote")
        scope.runCurrent()
        assertEquals(listOf("n" to "hello FloatNote"), added)
    }

    @Test
    fun `blank speech is ignored`() {
        pipeline.submit("n", "   ")
        scope.runCurrent()
        assertEquals(emptyList<Pair<String, String>>(), added)
    }

    @Test
    fun `a clip goes to the history with its text`() {
        val clip = kotlin.io.path.createTempFile().toFile()
        pipeline.submit("n", "hello", audio = clip)
        scope.runCurrent()
        assertEquals(listOf(clip), audio)
        assertTrue(clip.exists())
    }

    @Test
    fun `a clip whose text ends up blank is deleted`() {
        cleanup = true
        cleaner = { _, _ -> " " }
        val clip = kotlin.io.path.createTempFile().toFile()
        pipeline.submit("n", "um", audio = clip)
        scope.runCurrent()
        assertTrue(added.isEmpty())
        assertFalse(clip.exists())
    }
}
