package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMarkdownTest {
    @Test
    fun `front matter, heading, paragraphs and lists`() {
        val note = Note(
            id = "n",
            title = "Trip",
            category = NoteCategory.Personal,
            pinned = true,
            blocks = listOf(
                Block.Text("a", "Leaving Friday."),
                Block.Checklist("b", "passport", checked = true),
                Block.Task("c", "book taxi\ncheck price"),
                Block.Text("d", " "),
                Block.Text("e", "Back Sunday."),
            ),
        )
        val expected = """
            ---
            category: personal
            pinned: true
            ---

            # Trip

            Leaving Friday.

            - [x] passport
            - [ ] book taxi
              check price

            Back Sunday.

        """.trimIndent()
        assertEquals(expected, NoteMarkdown.render(note))
    }

    @Test
    fun `a plain note has no front matter`() {
        assertEquals("hello\n", NoteMarkdown.render(Note("n", blocks = listOf(Block.Text("a", "hello")))))
    }

    @Test
    fun `file names are safe and untitled notes use their id`() {
        assertEquals("a b c.md", NoteMarkdown.fileName(Note("n", title = "a/b:c")))
        assertEquals("hidden.md", NoteMarkdown.fileName(Note("n", title = "..hidden")))
        assertEquals("Note 12345678.md", NoteMarkdown.fileName(Note("1234567890", title = " ")))
        assertEquals(63, NoteMarkdown.fileName(Note("n", title = "x".repeat(200))).length)
    }
}
