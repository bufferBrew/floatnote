package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotesBackupTest {
    private var counter = 0
    private val newId = { "id${++counter}" }

    @Test
    fun `round trip keeps content, state, mode and pins with fresh ids`() {
        val notes = listOf(
            Note(
                id = "old",
                title = "Trip",
                blocks = listOf(Block.Text("x", "Pack"), Block.Checklist("y", "Passport", true), Block.Task("z", "Taxi")),
                taskMode = TaskCompletionMode.KeepInPlace,
                pinned = true,
            ),
            Note(id = "old2"),
        )
        val back = NotesBackup.fromJson(NotesBackup.toJson(notes), newId)
        assertEquals(2, back.size)
        val trip = back[0]
        assertEquals("id4", trip.id) // blocks get ids first, then the note
        assertEquals("Trip", trip.title)
        assertEquals(TaskCompletionMode.KeepInPlace, trip.taskMode)
        assertEquals(true, trip.pinned)
        assertEquals(
            listOf(Block.Text("id1", "Pack"), Block.Checklist("id2", "Passport", true), Block.Task("id3", "Taxi")),
            trip.blocks,
        )
    }

    @Test
    fun `other files are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { NotesBackup.fromJson("hello") }
        assertThrows(IllegalArgumentException::class.java) { NotesBackup.fromJson("""{"notes":[]}""") }
    }
}
