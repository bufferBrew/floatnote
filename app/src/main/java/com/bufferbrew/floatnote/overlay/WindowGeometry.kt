package com.bufferbrew.floatnote.overlay

/** An overlay window's position and size in screen pixels (top-left origin). */
data class WindowRect(val x: Int, val y: Int, val width: Int, val height: Int) {

    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun movedBy(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy)

    /**
     * Shrinks to fit [area] if needed, then keeps the whole window inside it. [area] is the
     * usable screen: everything between the status bar and the navigation bar.
     */
    fun clampedTo(area: WindowRect): WindowRect {
        val w = width.coerceAtMost(area.width)
        val h = height.coerceAtMost(area.height)
        return WindowRect(
            x = x.coerceIn(area.x, area.right - w),
            y = y.coerceIn(area.y, area.bottom - h),
            width = w,
            height = h,
        )
    }

    /** Resizes from the bottom-right corner, never below the minimum or past [area]'s edge. */
    fun resizedBy(dw: Int, dh: Int, minWidth: Int, minHeight: Int, area: WindowRect): WindowRect {
        val maxW = area.right - x
        val maxH = area.bottom - y
        return copy(
            width = (width + dw).coerceIn(minWidth.coerceAtMost(maxW), maxW),
            height = (height + dh).coerceIn(minHeight.coerceAtMost(maxH), maxH),
        )
    }
}
