package com.bufferbrew.floatnote.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root of the overlay window. Sees every touch and key before Compose does, so the window can
 * hand keyboard focus back and forth with the app underneath:
 * - a touch outside the window (needs FLAG_WATCH_OUTSIDE_TOUCH) gives focus back to that app,
 *   so you can keep typing there;
 * - a touch inside takes focus again, so the note's text fields can raise the keyboard;
 * - Back minimises instead of being swallowed.
 */
@SuppressLint("ViewConstructor")
class OverlayRootView(
    context: Context,
    private val onOutsideTouch: () -> Unit,
    private val onInsideTouch: () -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(context) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                onOutsideTouch()
                return true
            }
            MotionEvent.ACTION_DOWN -> onInsideTouch()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
