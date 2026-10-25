package com.bufferbrew.floatnote.overlay

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowGeometryTest {

    // 1440x3120 screen with a 128 px status bar and 52 px navigation bar.
    private val area = WindowRect(0, 128, 1440, 3120 - 128 - 52)

    @Test
    fun `window inside the area is unchanged`() {
        val rect = WindowRect(100, 200, 500, 800)
        assertEquals(rect, rect.clampedTo(area))
    }

    @Test
    fun `dragging past an edge stops at the edge, not under the system bars`() {
        assertEquals(WindowRect(0, 128, 500, 800), WindowRect(-50, -10, 500, 800).clampedTo(area))
        assertEquals(
            WindowRect(940, 3068 - 800, 500, 800),
            WindowRect(1200, 3000, 500, 800).clampedTo(area),
        )
    }

    @Test
    fun `window larger than the area shrinks to fit, e g after rotation`() {
        val landscape = WindowRect(0, 128, 3120, 1440 - 128)
        val rect = WindowRect(300, 300, 900, 1500).clampedTo(landscape)
        assertEquals(WindowRect(300, 128, 900, 1312), rect)
    }

    @Test
    fun `resize respects minimum size`() {
        val rect = WindowRect(100, 200, 600, 700).resizedBy(-1000, -1000, 300, 400, area)
        assertEquals(WindowRect(100, 200, 300, 400), rect)
    }

    @Test
    fun `resize stops at the area edge`() {
        val rect = WindowRect(100, 200, 600, 700).resizedBy(5000, 5000, 300, 400, area)
        assertEquals(WindowRect(100, 200, 1340, 3068 - 200), rect)
    }

    @Test
    fun `resize near the edge with no room for the minimum still fits`() {
        val rect = WindowRect(1260, 200, 180, 700).resizedBy(-50, 0, 300, 400, area)
        assertEquals(180, rect.width)
    }

    @Test
    fun `the corner can shrink the panel to a single button`() {
        val rect = WindowRect(100, 200, 600, 700).resizedBy(-2000, -2000, 120, 120, area)
        assertEquals(WindowRect(100, 200, 120, 120), rect)
    }

    @Test
    fun `too narrow or too short for the note means the record pill`() {
        assertFalse(WindowRect(0, 0, 500, 450).isCompact(500, 450))
        assertTrue(WindowRect(0, 0, 499, 900).isCompact(500, 450))
        assertTrue(WindowRect(0, 0, 900, 449).isCompact(500, 450))
    }

    @Test
    fun `the recording pill widens in place and stays on screen`() {
        assertEquals(WindowRect(100, 300, 400, 130), WindowRect(100, 300, 130, 130).grownTo(400, 120, area))
        // At the right edge it moves left rather than running off the screen.
        assertEquals(WindowRect(1040, 300, 400, 130), WindowRect(1300, 300, 130, 130).grownTo(400, 120, area))
        // Already big enough: unchanged.
        assertEquals(WindowRect(100, 300, 500, 200), WindowRect(100, 300, 500, 200).grownTo(400, 120, area))
    }
}
