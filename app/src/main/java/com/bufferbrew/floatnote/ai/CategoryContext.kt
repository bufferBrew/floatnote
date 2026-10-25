package com.bufferbrew.floatnote.ai

import android.content.Context
import androidx.annotation.StringRes
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.NoteCategory

/**
 * What ✦ Organise should know about each [NoteCategory]: your projects, people and
 * abbreviations. Edited in Settings → AI; until then each category has a built-in default.
 * Sent to the AI server only with notes in that category (or, for a note with no category, all
 * of them, so the model can suggest one).
 */
class CategoryContext(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("category_context", Context.MODE_PRIVATE)

    fun get(category: NoteCategory): String = prefs.getString(category.key, null) ?: default(category)

    /** Saving the default (or nothing changed from it) clears the override, so later defaults apply. */
    fun set(category: NoteCategory, text: String) {
        if (text.trim() == default(category).trim()) prefs.edit().remove(category.key).apply()
        else prefs.edit().putString(category.key, text).apply()
    }

    fun default(category: NoteCategory): String = app.getString(category.defaultContextRes)
}

@get:StringRes
val NoteCategory.labelRes: Int
    get() = when (this) {
        NoteCategory.Work -> R.string.category_work
        NoteCategory.Personal -> R.string.category_personal
        NoteCategory.BrewProjects -> R.string.category_brew
        NoteCategory.PersonalGithub -> R.string.category_github
    }

@get:StringRes
private val NoteCategory.defaultContextRes: Int
    get() = when (this) {
        NoteCategory.Work -> R.string.category_context_work
        NoteCategory.Personal -> R.string.category_context_personal
        NoteCategory.BrewProjects -> R.string.category_context_brew
        NoteCategory.PersonalGithub -> R.string.category_context_github
    }
