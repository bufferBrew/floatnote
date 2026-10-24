package com.bufferbrew.floatnote.domain.model

/** One line of note content. Ids are stable across edits and reorders. */
sealed interface Block {
    val id: String
    val text: String

    /** Plain paragraph. */
    data class Text(override val id: String, override val text: String) : Block

    /** Checking strikes the text through; the item never moves. */
    data class Checklist(
        override val id: String,
        override val text: String,
        val checked: Boolean = false,
    ) : Block

    /** Checkbox task; where it goes when done depends on [Note.taskMode]. */
    data class Task(
        override val id: String,
        override val text: String,
        val done: Boolean = false,
    ) : Block
}

enum class BlockType { Text, Checklist, Task }

val Block.type: BlockType
    get() = when (this) {
        is Block.Text -> BlockType.Text
        is Block.Checklist -> BlockType.Checklist
        is Block.Task -> BlockType.Task
    }

/** What happens to a [Block.Task] when it is completed. Set per note. */
enum class TaskCompletionMode {
    /** Done tasks sink to the end of their task group. */
    MoveToBottom,

    /** Done tasks stay where they are. */
    KeepInPlace,
}

data class Note(
    val id: String,
    val title: String = "",
    val blocks: List<Block> = emptyList(),
    val taskMode: TaskCompletionMode = TaskCompletionMode.MoveToBottom,
)
