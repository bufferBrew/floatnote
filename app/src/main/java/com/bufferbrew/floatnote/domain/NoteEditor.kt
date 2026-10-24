package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import java.util.UUID

/**
 * Pure edit operations on a [Note]. Both the full-screen editor and the floating overlay
 * go through this class, so the two can't drift apart.
 *
 * A "task group" is a maximal run of adjacent [Block.Task] blocks. In
 * [TaskCompletionMode.MoveToBottom], completing a task moves it to the end of its group,
 * and un-completing moves it back to just after the last open task in that group. Tasks never
 * jump across text or checklist blocks, so a note with several separate lists keeps each
 * list's items together.
 *
 * Unknown block ids are ignored and the note is returned unchanged.
 */
class NoteEditor(private val newId: () -> String = { UUID.randomUUID().toString() }) {

    fun toggle(note: Note, blockId: String): Note {
        val index = note.indexOf(blockId) ?: return note
        return when (val block = note.blocks[index]) {
            is Block.Text -> note
            is Block.Checklist -> note.replaceAt(index, block.copy(checked = !block.checked))
            is Block.Task -> {
                val toggled = block.copy(done = !block.done)
                when (note.taskMode) {
                    TaskCompletionMode.KeepInPlace -> note.replaceAt(index, toggled)
                    TaskCompletionMode.MoveToBottom -> note.repositionTask(index, toggled)
                }
            }
        }
    }

    fun updateText(note: Note, blockId: String, text: String): Note {
        val index = note.indexOf(blockId) ?: return note
        val updated = when (val block = note.blocks[index]) {
            is Block.Text -> block.copy(text = text)
            is Block.Checklist -> block.copy(text = text)
            is Block.Task -> block.copy(text = text)
        }
        return note.replaceAt(index, updated)
    }

    /**
     * Inserts an empty block after [afterId], or at the end when [afterId] is null or unknown.
     * Returns the new note and the new block's id, so the UI can focus it.
     */
    fun insertAfter(note: Note, afterId: String?, type: BlockType): Pair<Note, String> {
        val block = emptyBlock(type)
        val position = afterId?.let { note.indexOf(it) }?.plus(1) ?: note.blocks.size
        val blocks = note.blocks.toMutableList().apply { add(position, block) }
        return note.copy(blocks = blocks) to block.id
    }

    fun remove(note: Note, blockId: String): Note =
        note.copy(blocks = note.blocks.filterNot { it.id == blockId })

    /** Converts a block in place, keeping its id and text. Checked/done state resets. */
    fun changeType(note: Note, blockId: String, type: BlockType): Note {
        val index = note.indexOf(blockId) ?: return note
        val block = note.blocks[index]
        val converted = when (type) {
            BlockType.Text -> Block.Text(block.id, block.text)
            BlockType.Checklist -> Block.Checklist(block.id, block.text)
            BlockType.Task -> Block.Task(block.id, block.text)
        }
        return note.replaceAt(index, converted)
    }

    /** Adds transcribed speech as a new text block at the end. Blank input is ignored. */
    fun appendTranscript(note: Note, transcript: String): Note {
        val text = transcript.trim()
        if (text.isEmpty()) return note
        return note.copy(blocks = note.blocks + Block.Text(newId(), text))
    }

    /**
     * Switching to [TaskCompletionMode.MoveToBottom] sorts every task group so done tasks
     * sit at the end, keeping relative order. Switching to KeepInPlace changes nothing.
     */
    fun setTaskMode(note: Note, mode: TaskCompletionMode): Note {
        val withMode = note.copy(taskMode = mode)
        if (mode == TaskCompletionMode.KeepInPlace) return withMode
        val blocks = note.blocks.toMutableList()
        for (group in taskGroups(blocks)) {
            val sorted = blocks.subList(group.first, group.last + 1)
                .sortedBy { (it as Block.Task).done } // stable: keeps relative order
            sorted.forEachIndexed { offset, block -> blocks[group.first + offset] = block }
        }
        return withMode.copy(blocks = blocks)
    }

    private fun emptyBlock(type: BlockType): Block = when (type) {
        BlockType.Text -> Block.Text(newId(), "")
        BlockType.Checklist -> Block.Checklist(newId(), "")
        BlockType.Task -> Block.Task(newId(), "")
    }

    private fun Note.indexOf(blockId: String): Int? =
        blocks.indexOfFirst { it.id == blockId }.takeIf { it >= 0 }

    private fun Note.replaceAt(index: Int, block: Block): Note =
        copy(blocks = blocks.toMutableList().apply { set(index, block) })

    private fun Note.repositionTask(index: Int, toggled: Block.Task): Note {
        val group = taskGroups(blocks).first { index in it }
        val blocks = blocks.toMutableList().apply { removeAt(index) }
        // Group bounds after removing the task.
        val groupEnd = group.last - 1
        val target = if (toggled.done) {
            groupEnd + 1
        } else {
            val lastOpen = (group.first..groupEnd).lastOrNull { !(blocks[it] as Block.Task).done }
            (lastOpen ?: (group.first - 1)) + 1
        }
        blocks.add(target, toggled)
        return copy(blocks = blocks)
    }

    private fun taskGroups(blocks: List<Block>): List<IntRange> {
        val groups = mutableListOf<IntRange>()
        var start = -1
        blocks.forEachIndexed { i, block ->
            if (block is Block.Task) {
                if (start < 0) start = i
            } else if (start >= 0) {
                groups += start until i
                start = -1
            }
        }
        if (start >= 0) groups += start until blocks.size
        return groups
    }
}
