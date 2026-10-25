package com.bufferbrew.floatnote.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `opacity is kept within its range`() {
        val prefs = OverlayPrefs(context)
        assertEquals(1f, prefs.opacity)
        prefs.opacity = 0.05f
        assertEquals(OverlayPrefs.MIN_OPACITY, prefs.opacity)
    }

    @Test
    fun `geometry saved before the size presets is ignored once`() {
        context.getSharedPreferences("overlay", Context.MODE_PRIVATE).edit()
            .putInt("panel_x", 10).putInt("panel_y", 20).putInt("panel_w", 900).putInt("panel_h", 1400)
            .putInt("layout_version", 1).commit()
        val prefs = OverlayPrefs(context)
        assertNull(prefs.panel)
        prefs.panel = WindowRect(1, 2, 300, 400)
        assertEquals(WindowRect(1, 2, 300, 400), prefs.panel)
    }

    @Test
    fun `bubble position and the new-note option round-trip`() {
        val prefs = OverlayPrefs(context)
        assertNull(prefs.bubble)
        prefs.bubble = WindowRect(5, 6, 7, 8)
        assertEquals(WindowRect(5, 6, 7, 8), prefs.bubble)
        assertFalse(prefs.openNewNote)
        prefs.openNewNote = true
        assertTrue(prefs.openNewNote)
    }

    @Test
    fun `the last full size round-trips`() {
        val prefs = OverlayPrefs(context)
        assertNull(prefs.fullPanel)
        prefs.fullPanel = WindowRect(5, 6, 640, 820)
        assertEquals(WindowRect(5, 6, 640, 820), OverlayPrefs(context).fullPanel)
    }
}
