package com.bufferbrew.floatnote.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowGeometryTest {

    private val screenW = 1080
    private val screenH = 2400

    @Test
    fun `window inside the screen is unchanged`() {
        val rect = WindowRect(100, 200, 500, 800)
        assertEquals(rect, rect.clampedTo(screenW, screenH))
    }

    @Test
    fun `dragging past an edge stops at the edge`() {
        assertEquals(WindowRect(0, 0, 500, 800), WindowRect(-50, -10, 500, 800).clampedTo(screenW, screenH))
        assertEquals(
            WindowRect(580, 1600, 500, 800),
            WindowRect(900, 2000, 500, 800).clampedTo(screenW, screenH),
        )
    }

    @Test
    fun `window larger than the screen shrinks to fit, e g after rotation`() {
        // Portrait 1080x2400 rotated to landscape 2400x1080: too tall now, so it shrinks.
        val rect = WindowRect(300, 300, 900, 1500).clampedTo(screenWidth = 2400, screenHeight = 1080)
        assertEquals(WindowRect(300, 0, 900, 1080), rect)
    }

    @Test
    fun `resize respects minimum size`() {
        val rect = WindowRect(100, 100, 600, 700).resizedBy(-1000, -1000, 300, 400, screenW, screenH)
        assertEquals(WindowRect(100, 100, 300, 400), rect)
    }

    @Test
    fun `resize stops at the screen edge`() {
        val rect = WindowRect(100, 100, 600, 700).resizedBy(5000, 5000, 300, 400, screenW, screenH)
        assertEquals(WindowRect(100, 100, 980, 2300), rect)
    }

    @Test
    fun `resize near the edge with no room for the minimum still fits`() {
        val rect = WindowRect(900, 100, 180, 700).resizedBy(-50, 0, 300, 400, screenW, screenH)
        assertEquals(180, rect.width)
    }
}
