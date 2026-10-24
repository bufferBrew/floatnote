package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Notes to and from a JSON backup file the user keeps (Settings → Backup). Pure, so the format
 * is unit-tested. Imported notes get fresh ids, so importing never overwrites a note you have:
 * importing the same file twice gives duplicates rather than lost edits.
 */
object NotesBackup {
    const val VERSION = 1

    fun toJson(notes: List<Note>): String {
        val array = JSONArray()
        notes.forEach { note ->
            val blocks = JSONArray()
            note.blocks.forEach { block ->
                val (type, checked) = when (block) {
                    is Block.Text -> "text" to false
                    is Block.Checklist -> "checklist" to block.checked
                    is Block.Task -> "task" to block.done
                }
                blocks.put(JSONObject().put("type", type).put("text", block.text).put("checked", checked))
            }
            array.put(
                JSONObject()
                    .put("title", note.title)
                    .put("taskMode", note.taskMode.name)
                    .put("pinned", note.pinned)
                    .put("blocks", blocks)
            )
        }
        return JSONObject().put("app", "FloatNote").put("version", VERSION).put("notes", array).toString(2)
    }

    /** Throws [IllegalArgumentException] if [json] isn't a FloatNote backup. */
    fun fromJson(json: String, newId: () -> String = { UUID.randomUUID().toString() }): List<Note> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Not a FloatNote backup file.", e)
        }
        val notes = root.optJSONArray("notes")
        if (root.optString("app") != "FloatNote" || notes == null) {
            throw IllegalArgumentException("Not a FloatNote backup file.")
        }
        return (0 until notes.length()).mapNotNull { i ->
            val item = notes.optJSONObject(i) ?: return@mapNotNull null
            val array = item.optJSONArray("blocks") ?: JSONArray()
            val blocks = (0 until array.length()).mapNotNull { j ->
                val b = array.optJSONObject(j) ?: return@mapNotNull null
                val text = b.optString("text")
                val checked = b.optBoolean("checked")
                when (b.optString("type")) {
                    "checklist" -> Block.Checklist(newId(), text, checked)
                    "task" -> Block.Task(newId(), text, checked)
                    else -> Block.Text(newId(), text)
                }
            }
            Note(
                id = newId(),
                title = item.optString("title"),
                blocks = blocks,
                taskMode = TaskCompletionMode.entries.firstOrNull { it.name == item.optString("taskMode") }
                    ?: TaskCompletionMode.MoveToBottom,
                pinned = item.optBoolean("pinned"),
            )
        }
    }
}
