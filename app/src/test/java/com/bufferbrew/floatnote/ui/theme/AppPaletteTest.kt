package com.bufferbrew.floatnote.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPaletteTest {
    @Test
    fun savedKeysRoundTrip() {
        AppPalette.entries.forEach { assertEquals(it, AppPalette.fromKey(it.key)) }
    }

    @Test
    fun missingOrUnknownKeyFallsBackToIndigo() {
        assertEquals(AppPalette.Indigo, AppPalette.fromKey(null))
        assertEquals(AppPalette.Indigo, AppPalette.fromKey("no-such-theme"))
    }
}
