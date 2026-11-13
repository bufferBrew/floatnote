package com.bufferbrew.perch.ui.theme

import android.content.Context

/** The colour theme the user chose. SharedPreferences, like the other small settings. */
class AppearancePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    var palette: AppPalette
        get() = AppPalette.fromKey(prefs.getString(KEY_PALETTE, null))
        set(value) = prefs.edit().putString(KEY_PALETTE, value.key).apply()

    private companion object {
        const val KEY_PALETTE = "palette"
    }
}
