package com.bufferbrew.floatnote.data

import com.bufferbrew.floatnote.data.db.BlockEntity
import com.bufferbrew.floatnote.data.db.NoteEntity
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.BlockType
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.domain.model.TaskCompletionMode
import com.bufferbrew.floatnote.domain.model.type

fun Note.toEntity(updatedAt: Long) = NoteEntity(
    id = id,
    title = title,
    taskMode = taskMode.name,
    updatedAt = updatedAt,
    pinned = pinned,
    category = category?.key,
)

fun Note.toBlockEntities(): List<BlockEntity> = blocks.mapIndexed { position, block ->
    BlockEntity(
        id = block.id,
        noteId = id,
        position = position,
        type = block.type.name,
        text = block.text,
        checked = when (block) {
            is Block.Text -> false
            is Block.Checklist -> block.checked
            is Block.Task -> block.done
        },
    )
}

/** Unknown enum names (e.g. from a newer app version) fall back to safe defaults. */
fun NoteEntity.toDomain(blocks: List<BlockEntity>) = Note(
    id = id,
    title = title,
    blocks = blocks.sortedBy { it.position }.map { it.toDomain() },
    taskMode = TaskCompletionMode.entries.firstOrNull { it.name == taskMode }
        ?: TaskCompletionMode.MoveToBottom,
    pinned = pinned,
    category = NoteCategory.fromKey(category),
)

private fun BlockEntity.toDomain(): Block =
    when (BlockType.entries.firstOrNull { it.name == type } ?: BlockType.Text) {
        BlockType.Text -> Block.Text(id, text)
        BlockType.Checklist -> Block.Checklist(id, text, checked)
        BlockType.Task -> Block.Task(id, text, checked)
    }
