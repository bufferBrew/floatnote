package com.bufferbrew.floatnote.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.ui.theme.FloatNoteTheme

enum class OverlayMode { Panel, Bubble }

/**
 * The floating window: one WindowManager window that is either the note panel or the
 * minimised bubble. Switching modes changes the window's size, position and flags in place.
 *
 * The panel is focusable while you're using it (so the keyboard works, including Wispr Flow)
 * and gives focus back to the app underneath when you touch outside it. The bubble is never
 * focusable. Opacity is the window's own alpha, so it applies to everything drawn in it.
 */
class OverlayWindow(
    private val context: Context,
    private val session: NoteViewModel,
    private val prefs: OverlayPrefs,
    private val onClose: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner()

    private var mode by mutableStateOf(OverlayMode.Panel)
    private var opacity by mutableFloatStateOf(prefs.opacity)
    private var focusable = true

    private val bubbleSize = dp(56)
    private var panel = prefs.panel ?: defaultPanel()
    private var bubble = prefs.bubble ?: defaultBubble()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private val root = OverlayRootView(
        context = context,
        onOutsideTouch = { setFocusable(false) },
        onInsideTouch = { if (mode == OverlayMode.Panel) setFocusable(true) },
        onBack = ::minimise,
    )
    private var attached = false

    /** Adds the window if needed and shows the panel. */
    fun show() {
        if (!attached) {
            owner.start()
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
            root.addView(ComposeView(context).apply { setContent { Content() } })
            applyLayout()
            windowManager.addView(root, params)
            attached = true
        }
        expand()
    }

    fun expand() {
        session.ensureOpen()
        mode = OverlayMode.Panel
        focusable = true
        update()
    }

    fun minimise() {
        session.flush()
        mode = OverlayMode.Bubble
        update()
    }

    /** Screen size or orientation changed: pull both windows back on screen. */
    fun onScreenChanged() {
        val (w, h) = screenSize()
        panel = panel.clampedTo(w, h)
        bubble = bubble.clampedTo(w, h)
        update()
    }

    fun destroy() {
        session.flush()
        if (attached) windowManager.removeView(root)
        attached = false
        owner.destroy()
    }

    @Composable
    private fun Content() {
        FloatNoteTheme {
            when (mode) {
                OverlayMode.Bubble -> OverlayBubble(
                    onTap = ::expand,
                    onDrag = { dx, dy -> moveBubble(dx, dy) },
                    onDragEnd = { prefs.bubble = bubble },
                )
                OverlayMode.Panel -> OverlayPanel(
                    session = session,
                    opacity = opacity,
                    onOpacityChange = ::changeOpacity,
                    onOpacityChangeFinished = { prefs.opacity = opacity },
                    onMove = { dx, dy -> movePanel(dx, dy) },
                    onResize = { dx, dy -> resizePanel(dx, dy) },
                    onGeometryDone = { prefs.panel = panel },
                    onMinimise = ::minimise,
                    onClose = onClose,
                )
            }
        }
    }

    private fun changeOpacity(value: Float) {
        opacity = value.coerceIn(OverlayPrefs.MIN_OPACITY, 1f)
        update()
    }

    private fun movePanel(dx: Int, dy: Int) {
        val (w, h) = screenSize()
        panel = panel.movedBy(dx, dy).clampedTo(w, h)
        update()
    }

    private fun resizePanel(dx: Int, dy: Int) {
        val (w, h) = screenSize()
        panel = panel.resizedBy(dx, dy, dp(MIN_PANEL_WIDTH_DP), dp(MIN_PANEL_HEIGHT_DP), w, h)
        update()
    }

    private fun moveBubble(dx: Int, dy: Int) {
        val (w, h) = screenSize()
        bubble = bubble.movedBy(dx, dy).clampedTo(w, h)
        update()
    }

    private fun setFocusable(value: Boolean) {
        if (focusable == value) return
        focusable = value
        update()
    }

    private fun update() {
        if (!attached) return
        applyLayout()
        windowManager.updateViewLayout(root, params)
    }

    private fun applyLayout() {
        val rect = if (mode == OverlayMode.Panel) panel else bubble
        params.x = rect.x
        params.y = rect.y
        params.width = rect.width
        params.height = rect.height
        params.alpha = opacity
        val canFocus = mode == OverlayMode.Panel && focusable
        params.flags = if (canFocus) {
            params.flags and FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or FLAG_NOT_FOCUSABLE
        }
    }

    /**
     * Upper part of the screen by default: the keyboard opens over the bottom, and an overlay
     * window isn't pushed up by it the way an Activity is.
     */
    private fun defaultPanel(): WindowRect {
        val (w, h) = screenSize()
        val width = (w * 0.9f).toInt().coerceAtMost(dp(420))
        val height = (h * 0.45f).toInt()
        return WindowRect((w - width) / 2, (h * 0.08f).toInt(), width, height).clampedTo(w, h)
    }

    private fun defaultBubble(): WindowRect {
        val (w, h) = screenSize()
        return WindowRect(w - bubbleSize, h / 3, bubbleSize, bubbleSize)
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = context.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val MIN_PANEL_WIDTH_DP = 220
        const val MIN_PANEL_HEIGHT_DP = 200
    }
}
