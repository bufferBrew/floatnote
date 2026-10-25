package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.NoteEditor
import com.bufferbrew.floatnote.util.Strings
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.voice.LlmConfig
import java.util.UUID

/** Rewrites a whole note into tidy blocks. Interface so the session can be tested with a fake. */
fun interface NoteOrganiser {
    /** Throws [AiException] with a user-facing message on any failure. */
    suspend fun organise(note: Note): Note
}

/**
 * The AI step, like Wispr Flow's clean-up: [cleanUp] tidies one dictation before it's added;
 * [organise] restructures a whole note into text, checklist items and tasks, using the note's
 * category (its context text and the titles of the other notes in it) to understand names and
 * projects, and to suggest a category and a few next steps.
 * [config] is null when no API key is saved.
 */
class NoteAssistant(
    private val strings: Strings,
    private val model: ChatModel,
    private val config: () -> LlmConfig?,
    /** Why [config] is null, worded for the user. */
    private val configProblem: () -> String? = { null },
    private val editor: NoteEditor = NoteEditor(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** What the user told us about each category (Settings → AI). */
    private val categoryContext: (NoteCategory) -> String = { "" },
    /** Titles of the other notes in the note's category. */
    private val relatedTitles: suspend (Note) -> List<String> = { emptyList() },
    /** Whether Organise may add suggested points. */
    private val addSuggestions: () -> Boolean = { false },
) : NoteOrganiser {

    override suspend fun organise(note: Note): Note {
        val cfg = config() ?: throw AiException(
            configProblem()?.let { strings.get(R.string.err_organise_blocked, it) } ?: strings.get(R.string.err_no_key),
            needsSettings = true,
        )
        val suggest = addSuggestions()
        val system = organisePrompt(note, relatedTitles(note), suggest)
        val reply = model.complete(system, NoteFormat.toJson(note), cfg, json = true)
        val organised = NoteFormat.fromJson(reply, note, newId, strings, maxAdded = if (suggest) MAX_ADDED else 0)
        // Re-apply the note's task mode, so done tasks sit where the user expects.
        return editor.setTaskMode(organised, organised.taskMode)
    }

    /**
     * The organise rules plus what's known about the note: its category's context, or every
     * category's when it has none (so the model can pick one).
     */
    internal fun organisePrompt(note: Note, related: List<String>, suggest: Boolean): String = buildString {
        append(ORGANISE_PROMPT)
        append(if (suggest) SUGGEST_RULE else NO_SUGGEST_RULE)
        append("\n\nContext about this person. Use it to understand names, projects and abbreviations; ")
        append("don't copy it into the note.\n")
        val category = note.category
        if (category != null) {
            append("The note's category is \"${category.key}\". Keep it.\n")
            categoryContext(category).trim().takeIf { it.isNotEmpty() }?.let { append("About ${category.key}: $it\n") }
            if (related.isNotEmpty()) {
                append("Their other notes in this category: ")
                append(related.joinToString(", ") { "\"$it\"" })
                append(".\n")
            }
        } else {
            append("The note has no category. Set \"category\" to the one that clearly fits, or \"\" if none does:\n")
            NoteCategory.entries.forEach { c ->
                append("- ${c.key}: ${categoryContext(c).trim().ifEmpty { c.name }}\n")
            }
        }
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
            {"title": string, "category": string, "blocks": [{"type": "text"|"checklist"|"task", "text": string, "checked": boolean}]}
            Return the organised note as JSON in the same shape plus an "added" array of strings, and nothing else.
            Rules:
            - Keep every fact, name, number and date. Never invent content in "blocks" or drop information.
            - Clean up dictated sentences: remove filler words, fix grammar and punctuation.
            - Group related lines together. Merge duplicates.
            - Things to do become "task" blocks; lists of items (shopping, packing) become "checklist" blocks;
              everything else stays "text". One item per block.
            - Keep "checked" true for items that were already checked or done.
            - If the title is empty, give a short title (max 6 words). Otherwise keep the title.
            - Write in the same language as the note.
        """.trimIndent()

        // Starts with a newline: it continues ORGANISE_PROMPT's rule list.
        val SUGGEST_RULE = "\n" + """
            - "added": up to $MAX_ADDED short points that would help the person, based on the note and the
              context below: a next step, a detail still to decide, a related project or note. Each must
              be new (not a repeat of the note) and specific. Don't state anything you're unsure of as fact.
              Use [] when nothing is clearly useful. Lines starting with "${NoteFormat.ADDED_MARK.trim()}" were
              suggested before: keep them as they are.
        """.trimIndent()

        val NO_SUGGEST_RULE = "\n- \"added\": always []."

        const val MAX_ADDED = 3
    }
}
