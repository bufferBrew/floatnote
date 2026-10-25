package com.bufferbrew.floatnote.overlay

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.hypot

/**
 * Drag (and optional tap) that moves or resizes the overlay window itself.
 *
 * Compose's own drag gestures measure in the view's local coordinates. Moving the window under
 * the finger shifts those coordinates by the same amount, which halves or cancels the drag and
 * makes the window jitter. Screen coordinates (`rawX`/`rawY`) don't move with the window, so
 * this reads the MotionEvent directly.
 *
 * [onDrag] gets the *total* offset since the finger went down, not a per-event step. The caller
 * places the window at (position at [onDragStart] + offset), clamped. Adding up steps instead
 * lets the finger and window drift apart once the window is stopped by a screen edge, which
 * feels like the window is stuck.
 *
 * [onLongPress], if given, fires when the finger stays put for the long-press timeout; that
 * touch then neither drags nor taps.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.windowDrag(
    onDragStart: () -> Unit,
    onDrag: (totalDx: Int, totalDy: Int) -> Unit,
    onDragEnd: () -> Unit = {},
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
): Modifier = composed {
    val config = LocalViewConfiguration.current
    val touchSlop = config.touchSlop
    val view = LocalView.current
    // Plain object, not state: these change on every move and nothing recomposes on them.
    val track = remember { DragTracker() }
    val currentLongPress by rememberUpdatedState(onLongPress)
    val longPress = remember {
        Runnable {
            track.longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            currentLongPress?.invoke()
        }
    }
    DisposableEffect(view) { onDispose { view.removeCallbacks(longPress) } }
    pointerInteropFilter { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                track.down(event.rawX, event.rawY)
                if (onLongPress != null) view.postDelayed(longPress, config.longPressTimeoutMillis)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!track.dragging && !track.longPressed &&
                    hypot(event.rawX - track.downX, event.rawY - track.downY) > touchSlop
                ) {
                    view.removeCallbacks(longPress)
                    track.dragging = true
                    onDragStart()
                }
                if (track.dragging) {
                    onDrag((event.rawX - track.downX).toInt(), (event.rawY - track.downY).toInt())
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPress)
                when {
                    track.longPressed -> Unit
                    track.dragging -> onDragEnd()
                    else -> onTap?.invoke()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPress)
                if (track.dragging) onDragEnd()
                true
            }
            else -> false
        }
    }
}

private class DragTracker {
    var downX = 0f
    var downY = 0f
    var dragging = false
    var longPressed = false

    fun down(x: Float, y: Float) {
        downX = x
        downY = y
        dragging = false
        longPressed = false
    }
}
