package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON shape notes travel in to and from the AI model:
 * `{"title": "...", "blocks": [{"type": "text|checklist|task", "text": "...", "checked": false}]}`.
 * Pure, so the parsing rules are unit-tested.
 */
object NoteFormat {

    fun toJson(note: Note): String {
        val blocks = JSONArray()
        note.blocks.filter { it.text.isNotBlank() }.forEach { block ->
            val (type, checked) = when (block) {
                is Block.Text -> "text" to false
                is Block.Checklist -> "checklist" to block.checked
                is Block.Task -> "task" to block.done
            }
            blocks.put(JSONObject().put("type", type).put("text", block.text).put("checked", checked))
        }
        return JSONObject().put("title", note.title).put("blocks", blocks).toString()
    }

    /**
     * Reads the model's reply into [base]'s id and task mode, with fresh block ids. Throws
     * [AiException] if the reply isn't usable, so the note is never replaced with garbage.
     */
    fun fromJson(reply: String, base: Note, newId: () -> String): Note {
        val json = runCatching { JSONObject(stripFence(reply)) }
            .getOrElse { throw AiException("The AI reply wasn't a note. Try again.", it) }
        val array = json.optJSONArray("blocks") ?: throw AiException("The AI reply had no note content. Try again.")
        val blocks = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val text = item.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            val checked = item.optBoolean("checked", false)
            when (item.optString("type").lowercase()) {
                "checklist" -> Block.Checklist(newId(), text, checked)
                "task" -> Block.Task(newId(), text, checked)
                else -> Block.Text(newId(), text)
            }
        }
        if (blocks.isEmpty() && base.blocks.any { it.text.isNotBlank() }) {
            throw AiException("The AI reply was empty, so your note was left as it was.")
        }
        val title = json.optString("title").trim().ifEmpty { base.title }
        return base.copy(title = title, blocks = blocks)
    }

    /** Some models wrap JSON in a ```json fence even when asked not to. */
    private fun stripFence(reply: String): String =
        reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
