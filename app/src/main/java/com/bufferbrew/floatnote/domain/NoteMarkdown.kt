package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note

/**
 * A note as a Markdown file, for the export folder (an Obsidian vault works): front matter for
 * the category and pin, the title as a heading, paragraphs, and `- [ ]` / `- [x]` items for
 * checklists and tasks.
 */
object NoteMarkdown {
    fun render(note: Note): String {
        val out = StringBuilder()
        val front = listOfNotNull(
            note.category?.let { "category: ${it.key}" },
            "pinned: true".takeIf { note.pinned },
        )
        if (front.isNotEmpty()) {
            out.append("---\n")
            front.forEach { out.append(it).append('\n') }
            out.append("---\n\n")
        }
        if (note.title.isNotBlank()) out.append("# ").append(note.title.trim()).append("\n\n")
        var previousWasItem: Boolean? = null
        for (block in note.blocks) {
            val text = block.text.trim()
            if (text.isEmpty()) continue
            val item = block !is Block.Text
            // Items of one list sit together; anything else is a paragraph of its own.
            if (previousWasItem != null && !(previousWasItem && item)) out.append('\n')
            when (block) {
                is Block.Text -> out.append(text)
                is Block.Checklist -> out.append(item(block.checked, text))
                is Block.Task -> out.append(item(block.done, text))
            }
            out.append('\n')
            previousWasItem = item
        }
        return out.toString().trimEnd() + "\n"
    }

    /** Continuation lines are indented so they stay part of the item. */
    private fun item(done: Boolean, text: String) =
        (if (done) "- [x] " else "- [ ] ") + text.replace("\n", "\n  ")

    /** A file name from the title, safe on any provider; untitled notes use their id. */
    fun fileName(note: Note): String {
        val base = note.title
            .replace(UNSAFE, " ")
            .replace(SPACES, " ")
            .trim()
            .trimStart('.')
            .take(MAX_NAME)
            .trim()
        return base.ifEmpty { "Note ${note.id.take(8)}" } + ".md"
    }

    private val UNSAFE = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val SPACES = Regex("""\s+""")
    private const val MAX_NAME = 60
}
