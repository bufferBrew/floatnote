package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.domain.NoteEditor
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.voice.LlmConfig
import java.util.UUID

/** Rewrites a whole note into tidy blocks. Interface so the session can be tested with a fake. */
fun interface NoteOrganiser {
    /** Throws [AiException] with a user-facing message on any failure. */
    suspend fun organise(note: Note): Note
}

/**
 * The AI step, like Wispr Flow's clean-up: [cleanUp] tidies one dictation before it's added;
 * [organise] restructures a whole note into text, checklist items and tasks.
 * [config] is null when no API key is saved.
 */
class NoteAssistant(
    private val model: ChatModel,
    private val config: () -> LlmConfig?,
    /** Why [config] is null, worded for the user. */
    private val configProblem: () -> String? = { null },
    private val editor: NoteEditor = NoteEditor(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : NoteOrganiser {

    override suspend fun organise(note: Note): Note {
        val cfg = config() ?: throw AiException(
            configProblem()?.let { "Organise can't run: $it" } ?: "Organise needs an API key. Add one in Settings.",
            needsSettings = true,
        )
        val reply = model.complete(ORGANISE_PROMPT, NoteFormat.toJson(note), cfg, json = true)
        val organised = NoteFormat.fromJson(reply, note, newId)
        // Re-apply the note's task mode, so done tasks sit where the user expects.
        return editor.setTaskMode(organised, organised.taskMode)
    }

    /** Returns [text] unchanged if there's no key; throws [AiException] if the call fails. */
    suspend fun cleanUp(text: String, vocabulary: List<String>): String {
        val cfg = config() ?: return text
        val system = if (vocabulary.isEmpty()) CLEANUP_PROMPT else
            CLEANUP_PROMPT + "\nSpell these words exactly like this: " + vocabulary.joinToString(", ") + "."
        val cleaned = model.complete(system, text, cfg).trim()
        // A model that answers instead of rewriting tends to go long; keep what was said.
        return if (cleaned.isEmpty() || cleaned.length > text.length * 2 + 40) text else cleaned
    }

    private companion object {
        val CLEANUP_PROMPT = """
            You clean up dictated text. Remove filler words (um, uh, like, you know), false starts
            and repeated words. Fix punctuation, capitalisation and obvious mis-hearings. If the
            speaker corrects themselves ("no wait, Tuesday"), keep only the correction. Keep the
            speaker's wording, language and meaning; do not summarise, answer, or add anything.
            Reply with the cleaned text only.
        """.trimIndent()

        val ORGANISE_PROMPT = """
            You organise a person's quick notes. You receive a note as JSON:
            {"title": string, "blocks": [{"type": "text"|"checklist"|"task", "text": string, "checked": boolean}]}
            Return the organised note in exactly the same JSON shape and nothing else.
            Rules:
            - Keep every fact, name, number and date. Never invent content or drop information.
            - Clean up dictated sentences: remove filler words, fix grammar and punctuation.
            - Group related lines together. Merge duplicates.
            - Things to do become "task" blocks; lists of items (shopping, packing) become "checklist" blocks;
              everything else stays "text". One item per block.
            - Keep "checked" true for items that were already checked or done.
            - If the title is empty, give a short title (max 6 words). Otherwise keep the title.
            - Write in the same language as the note.
        """.trimIndent()
    }
}
