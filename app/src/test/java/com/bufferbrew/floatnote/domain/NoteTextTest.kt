package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTextTest {
    @Test
    fun `title, text and checkable items, blanks skipped`() {
        val note = Note(
            id = "n",
            title = "Trip",
            blocks = listOf(
                Block.Text("1", "Pack light"),
                Block.Checklist("2", "Passport", checked = true),
                Block.Task("3", "Book taxi"),
                Block.Text("4", "  "),
            ),
        )
        assertEquals("Trip\n\nPack light\n☑ Passport\n☐ Book taxi", NoteText.of(note))
    }

    @Test
    fun `no title, no leading blank line`() {
        assertEquals("hi", NoteText.of(Note("n", blocks = listOf(Block.Text("1", "hi")))))
    }
}
