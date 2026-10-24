package com.bufferbrew.floatnote.domain

import com.bufferbrew.floatnote.domain.model.VocabEntry

/**
 * Applies the user's vocabulary to speech. Pure, so it's unit-tested.
 *
 * Every entry is also a recognition hint ([whisperPrompt], [hints]); after recognition,
 * [apply] fixes what came back.
 */
object Vocabulary {

    /**
     * Whole-word, case-insensitive. A replacement entry writes its replacement; a bare word is
     * written in its saved spelling ("kubernetes" → "Kubernetes"). Longer terms go first, so
     * "New York Times" wins over "New York".
     */
    fun apply(text: String, entries: List<VocabEntry>): String {
        var result = text
        entries
            .filter { it.term.isNotBlank() }
            .sortedByDescending { it.term.length }
            .forEach { entry ->
                val target = entry.replacement.ifBlank { entry.term.trim() }
                result = wordPattern(entry.term.trim()).replace(result) { target }
            }
        return result
    }

    /**
     * Whisper's `prompt` biases it towards the spellings it sees there. Whisper only reads the
     * last ~224 tokens of the prompt, so this stays well under that.
     */
    fun whisperPrompt(entries: List<VocabEntry>): String {
        val words = hints(entries)
        if (words.isEmpty()) return ""
        val joined = StringBuilder()
        for (word in words) {
            if (joined.length + word.length + 2 > MAX_PROMPT_CHARS) break
            if (joined.isNotEmpty()) joined.append(", ")
            joined.append(word)
        }
        return "Vocabulary: $joined."
    }

    /** The spellings to listen for: the replacement when there is one, else the word. */
    fun hints(entries: List<VocabEntry>): List<String> =
        entries.map { it.replacement.ifBlank { it.term }.trim() }.filter { it.isNotEmpty() }.distinct()

    // Lookarounds rather than \b so terms that start or end with symbols ("C++", ".NET") match.
    private fun wordPattern(term: String) =
        Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(term) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)

    private const val MAX_PROMPT_CHARS = 600
}
