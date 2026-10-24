package com.bufferbrew.floatnote.voice

import com.bufferbrew.floatnote.domain.Vocabulary
import com.bufferbrew.floatnote.domain.model.VocabEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * What happens to recognised speech before it reaches the note: vocabulary fixes, then the
 * optional AI clean-up, then the note and the history.
 *
 * Dictations are handled one at a time in arrival order, so device sentences (which arrive
 * quickly, one per pause) land in the note in the order they were spoken even when each waits on
 * a network clean-up. A failed clean-up never loses speech: the uncleaned text is used instead.
 */
class TranscriptPipeline(
    scope: CoroutineScope,
    private val vocabulary: () -> List<VocabEntry>,
    private val cleanupEnabled: () -> Boolean,
    private val cleanUp: suspend (text: String, vocabulary: List<String>) -> String,
    private val addToNote: (noteId: String, text: String, continues: Boolean) -> Unit,
    private val addToHistory: suspend (noteId: String, text: String, raw: String) -> Unit,
) {
    private class Item(val noteId: String, val raw: String, val continues: Boolean)

    private val queue = Channel<Item>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in queue) process(item.noteId, item.raw, item.continues)
        }
    }

    /** [continues]: the next sentence of the same dictation (joins the previous paragraph). */
    fun submit(noteId: String, raw: String, continues: Boolean = false) {
        if (raw.isNotBlank()) queue.trySend(Item(noteId, raw, continues))
    }

    private suspend fun process(noteId: String, raw: String, continues: Boolean) {
        val entries = vocabulary()
        var text = Vocabulary.apply(raw.trim(), entries)
        if (cleanupEnabled()) {
            text = runCatching { cleanUp(text, Vocabulary.hints(entries)) }
                .getOrDefault(text)
                // The model may undo a vocabulary spelling; apply it again.
                .let { Vocabulary.apply(it, entries) }
        }
        if (text.isBlank()) return
        addToNote(noteId, text, continues)
        runCatching { addToHistory(noteId, text, raw.trim()) }
    }
}
