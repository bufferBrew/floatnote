package com.bufferbrew.floatnote.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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

    // A short pill, like Wispr Flow's floating bar.
    private val bubbleWidth = dp(BUBBLE_WIDTH_DP)
    private val bubbleHeight = dp(BUBBLE_HEIGHT_DP)
    private var panel = (prefs.panel ?: defaultPanel()).clampedTo(usableArea())
    // Saved positions may predate a bubble size change, so always apply the current size.
    private var bubble = (prefs.bubble ?: defaultBubble())
        .copy(width = bubbleWidth, height = bubbleHeight)
        .clampedTo(usableArea())

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Without these, Android quietly offsets an overlay below the status bar, so x/y stop
        // being screen coordinates and the edge limits are off by the status bar's height.
        flags = flags or FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
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
            root.addView(ComposeView(context).apply { setContent { OverlayContent() } })
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
        val area = usableArea()
        panel = panel.clampedTo(area)
        bubble = bubble.clampedTo(area)
        update()
    }

    fun destroy() {
        session.flush()
        if (attached) windowManager.removeView(root)
        attached = false
        owner.destroy()
    }

    @Composable
    private fun OverlayContent() {
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
        panel = panel.movedBy(dx, dy).clampedTo(usableArea())
        update()
    }

    private fun resizePanel(dx: Int, dy: Int) {
        panel = panel.resizedBy(dx, dy, dp(MIN_PANEL_WIDTH_DP), dp(MIN_PANEL_HEIGHT_DP), usableArea())
        update()
    }

    private fun moveBubble(dx: Int, dy: Int) {
        bubble = bubble.movedBy(dx, dy).clampedTo(usableArea())
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
        val area = usableArea()
        val width = (area.width * 0.9f).toInt().coerceAtMost(dp(420))
        val height = (area.height * 0.45f).toInt()
        val top = area.y + (area.height * 0.05f).toInt()
        return WindowRect(area.x + (area.width - width) / 2, top, width, height)
    }

    private fun defaultBubble(): WindowRect {
        val area = usableArea()
        return WindowRect(area.right - bubbleWidth - dp(8), area.y + area.height / 3, bubbleWidth, bubbleHeight)
    }

    /** The screen minus the status bar, navigation bar and camera cutout, in screen pixels. */
    private fun usableArea(): WindowRect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return WindowRect(
                x = insets.left,
                y = insets.top,
                width = bounds.width() - insets.left - insets.right,
                height = bounds.height() - insets.top - insets.bottom,
            )
        }
        val metrics = context.resources.displayMetrics
        return WindowRect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val MIN_PANEL_WIDTH_DP = 220
        const val MIN_PANEL_HEIGHT_DP = 200
        const val BUBBLE_WIDTH_DP = 64
        const val BUBBLE_HEIGHT_DP = 36
    }
}
