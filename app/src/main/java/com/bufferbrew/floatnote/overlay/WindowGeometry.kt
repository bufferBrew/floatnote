package com.bufferbrew.floatnote.overlay

/** An overlay window's position and size in screen pixels (top-left origin). */
data class WindowRect(val x: Int, val y: Int, val width: Int, val height: Int) {

    fun movedBy(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy)

    /** Shrinks to fit the screen if needed, then keeps the whole window on screen. */
    fun clampedTo(screenWidth: Int, screenHeight: Int): WindowRect {
        val w = width.coerceAtMost(screenWidth)
        val h = height.coerceAtMost(screenHeight)
        return WindowRect(
            x = x.coerceIn(0, screenWidth - w),
            y = y.coerceIn(0, screenHeight - h),
            width = w,
            height = h,
        )
    }

    /** Resizes from the bottom-right corner, never below the minimum or past the screen edge. */
    fun resizedBy(
        dw: Int,
        dh: Int,
        minWidth: Int,
        minHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): WindowRect = copy(
        width = (width + dw).coerceIn(minWidth.coerceAtMost(screenWidth - x), screenWidth - x),
        height = (height + dh).coerceIn(minHeight.coerceAtMost(screenHeight - y), screenHeight - y),
    )
}
