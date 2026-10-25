package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.util.Strings
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON shape notes travel in to and from the AI model:
 * `{"title": "...", "category": "work|personal|brew|github|", "blocks": [{"type": "text|checklist|task",
 * "text": "...", "checked": false}]}`. Replies may also carry `"added": ["..."]`, points the model
 * suggests, which become text lines starting with [ADDED_MARK].
 * Pure, so the parsing rules are unit-tested.
 */
object NoteFormat {
    /** Starts every line the AI added, so you can tell them from what you wrote. */
    const val ADDED_MARK = "✦ "

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
        return JSONObject()
            .put("title", note.title)
            .put("category", note.category?.key.orEmpty())
            .put("blocks", blocks)
            .toString()
    }

    /**
     * Reads the model's reply into [base]'s id and task mode, with fresh block ids. Throws
     * [AiException] if the reply isn't usable, so the note is never replaced with garbage.
     * The reply's category is used only when [base] has none: the user's choice wins. Up to
     * [maxAdded] suggested points are appended, marked with [ADDED_MARK].
     */
    fun fromJson(reply: String, base: Note, newId: () -> String, strings: Strings, maxAdded: Int = 0): Note {
        val json = runCatching { JSONObject(stripFence(reply)) }
            .getOrElse { throw AiException(strings.get(R.string.err_ai_not_note), it) }
        val array = json.optJSONArray("blocks") ?: throw AiException(strings.get(R.string.err_ai_no_content))
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
            throw AiException(strings.get(R.string.err_ai_empty))
        }
        val title = json.optString("title").trim().ifEmpty { base.title }
        val category = base.category ?: NoteCategory.fromKey(json.optString("category"))
        return base.copy(title = title, blocks = blocks + added(json, blocks, maxAdded, newId), category = category)
    }

    /** The reply's suggested points, minus blanks and lines the note already has. */
    private fun added(json: JSONObject, blocks: List<Block>, max: Int, newId: () -> String): List<Block> {
        val array = json.optJSONArray("added") ?: return emptyList()
        val existing = blocks.map { it.text.removePrefix(ADDED_MARK).trim().lowercase() }.toSet()
        return (0 until array.length())
            .map { array.optString(it).trim().removePrefix(ADDED_MARK.trim()).trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in existing }
            .distinct()
            .take(max)
            .map { Block.Text(newId(), ADDED_MARK + it) }
    }

    /** Some models wrap JSON in a ```json fence even when asked not to. */
    private fun stripFence(reply: String): String =
        reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
