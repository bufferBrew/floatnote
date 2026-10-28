package com.bufferbrew.floatnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingStoreTest {
    private val root: File = Files.createTempDirectory("rec").toFile()
    private val dir = File(root, "recordings")
    private var enabled = true

    private fun clip(name: String, bytes: Int = 10) = File(root, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a kept clip moves in under its own name`() {
        val store = RecordingStore(dir, { enabled })
        val clip = clip("clip-1-n.m4a")
        assertEquals("clip-1-n.m4a", store.keep(clip))
        assertFalse(clip.exists())
        assertTrue(store.file("clip-1-n.m4a")!!.isFile)
    }

    @Test
    fun `with keeping off the clip is deleted`() {
        enabled = false
        val clip = clip("clip-1-n.m4a")
        assertNull(RecordingStore(dir, { enabled }).keep(clip))
        assertFalse(clip.exists())
    }

    @Test
    fun `names can't reach outside the folder`() {
        val store = RecordingStore(dir, { enabled })
        store.keep(clip("clip-1-n.m4a"))
        clip("secret")
        assertNull(store.file("../secret"))
    }

    @Test
    fun `prune drops unreferenced files, then the oldest over the limit`() {
        val store = RecordingStore(dir, { enabled }, maxBytes = 25)
        listOf("a", "b", "c", "d").forEachIndexed { i, name ->
            store.keep(clip(name))
            File(dir, name).setLastModified(1_000L * (i + 1))
        }
        store.prune(setOf("a", "b", "c"))
        assertFalse(File(dir, "d").exists()) // not referenced
        assertFalse(File(dir, "a").exists()) // oldest, over 25 bytes
        assertTrue(File(dir, "b").exists() && File(dir, "c").exists())
        assertEquals(20, store.size())
    }
}
