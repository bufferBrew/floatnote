package com.bufferbrew.floatnote.overlay

import android.content.Context

/**
 * Remembers where the floating window and bubble were and how see-through the window is.
 * SharedPreferences rather than DataStore: the values are needed synchronously before the
 * window is first added, and they're tiny.
 */
class OverlayPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    var opacity: Float
        get() = prefs.getFloat(KEY_OPACITY, 1f).coerceIn(MIN_OPACITY, 1f)
        set(value) = prefs.edit().putFloat(KEY_OPACITY, value).apply()

    /**
     * Saved panel geometry. Geometry saved before the size presets existed (layout version 1) is
     * ignored once, so panels that were too big start over at the small default.
     */
    var panel: WindowRect?
        get() = if (prefs.getInt(KEY_LAYOUT_VERSION, 1) < LAYOUT_VERSION) null else readRect("panel")
        set(value) {
            writeRect("panel", value)
            prefs.edit().putInt(KEY_LAYOUT_VERSION, LAYOUT_VERSION).apply()
        }

    var bubble: WindowRect?
        get() = readRect("bubble")
        set(value) = writeRect("bubble", value)

    private fun readRect(name: String): WindowRect? {
        if (!prefs.contains("${name}_x")) return null
        return WindowRect(
            x = prefs.getInt("${name}_x", 0),
            y = prefs.getInt("${name}_y", 0),
            width = prefs.getInt("${name}_w", 0),
            height = prefs.getInt("${name}_h", 0),
        )
    }

    private fun writeRect(name: String, rect: WindowRect?) {
        val edit = prefs.edit()
        if (rect == null) {
            listOf("x", "y", "w", "h").forEach { edit.remove("${name}_$it") }
        } else {
            edit.putInt("${name}_x", rect.x)
                .putInt("${name}_y", rect.y)
                .putInt("${name}_w", rect.width)
                .putInt("${name}_h", rect.height)
        }
        edit.apply()
    }

    companion object {
        /** Below this the panel is too faint to read or find. */
        const val MIN_OPACITY = 0.3f
        private const val KEY_OPACITY = "opacity"
        private const val KEY_LAYOUT_VERSION = "layout_version"
        // 3: thinner presets; panels saved at the old sizes start over at the new small default.
        private const val LAYOUT_VERSION = 3
    }
}
