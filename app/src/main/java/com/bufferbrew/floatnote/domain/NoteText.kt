package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note

/** A note as plain text for sharing: checklist items and tasks as "☐ / ☑" lines. */
object NoteText {
    fun of(note: Note): String = buildString {
        if (note.title.isNotBlank()) appendLine(note.title.trim()).appendLine()
        note.blocks.filter { it.text.isNotBlank() }.forEach { block ->
            when (block) {
                is Block.Text -> appendLine(block.text.trim())
                is Block.Checklist -> appendLine((if (block.checked) "☑ " else "☐ ") + block.text.trim())
                is Block.Task -> appendLine((if (block.done) "☑ " else "☐ ") + block.text.trim())
            }
        }
    }.trimEnd()
}
