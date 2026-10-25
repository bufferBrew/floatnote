package com.bufferbrew.floatnote.domain.model

/**
 * What a note is about. Used to filter the note list and to tell ✦ Organise what the note is
 * for (each category has a context text, editable in Settings → AI). A note has at most one.
 * [key] is what's stored and what the AI model sees; never rename one, or saved notes lose it.
 */
enum class NoteCategory(val key: String) {
    Work("work"),
    Personal("personal"),
    BrewProjects("brew"),
    PersonalGithub("github"),
    ;

    companion object {
        /** Unknown or missing keys (a newer app version, a bad AI reply) read as no category. */
        fun fromKey(key: String?): NoteCategory? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}
