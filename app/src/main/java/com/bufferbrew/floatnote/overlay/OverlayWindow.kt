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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.ui.theme.FloatNoteTheme
import com.bufferbrew.floatnote.voice.AppVoiceActions
import com.bufferbrew.floatnote.voice.AutoRecordEffect
import com.bufferbrew.floatnote.voice.VoiceController

enum class OverlayMode { Panel, Bubble }

/**
 * The floating window: one WindowManager window that is either the note panel or the
 * minimised bubble. Switching modes changes the window's size, position and flags in place.
 *
 * The panel is focusable while you're using it (so the keyboard works, including Wispr Flow)
 * and gives focus back to the app underneath when you touch outside it. The bubble is never
 * focusable.
 *
 * Opacity fades what's drawn (a graphics layer), never the window: Android drops every touch
 * on a window whose own alpha is below its threshold ("Not sending motion … window opacity
 * below the threshold"), which made a faded panel completely unresponsive.
 */
class OverlayWindow(
    private val context: Context,
    private val session: NoteViewModel,
    private val voice: VoiceController,
    private val prefs: OverlayPrefs,
    private val onClose: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner()
    private val voiceActions = AppVoiceActions(context, beforeOpeningScreen = { minimise() })

    private var mode by mutableStateOf(OverlayMode.Panel)

    /** The preset the panel currently matches, for highlighting; null after a custom resize. */
    private var panelPreset by mutableStateOf<PanelSize?>(null)
    private var opacity by mutableFloatStateOf(prefs.opacity)
    private var focusable = true

    /** The panel is too small for the note and shows only the record pill. */
    private var compact by mutableStateOf(false)

    /** A corner resize is in progress (the grip must outlive a switch to or from [compact]). */
    private var resizing by mutableStateOf(false)

    // A short pill, like Wispr Flow's floating bar.
    private val bubbleWidth = dp(BUBBLE_WIDTH_DP)
    private val bubbleHeight = dp(BUBBLE_HEIGHT_DP)
    private var panel = (prefs.panel ?: defaultPanel()).clampedTo(usableArea())
    // Saved positions may predate a bubble size change, so always apply the current size.
    private var bubble = (prefs.bubble ?: defaultBubble())
        .copy(width = bubbleWidth, height = bubbleHeight)
        .clampedTo(usableArea())

    init {
        panelPreset = matchingPreset()
        compact = isCompact(panel)
    }

    private fun matchingPreset(): PanelSize? = PanelSize.entries.firstOrNull {
        preset(it.widthDp) == panel.width && preset(it.heightDp) == panel.height
    }

    private fun isCompact(rect: WindowRect) = rect.isCompact(dp(FULL_MIN_WIDTH_DP), dp(FULL_MIN_HEIGHT_DP))

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
        // Closing the floating note ends any recording and sends it.
        voice.stop()
        session.flush()
        if (attached) windowManager.removeView(root)
        attached = false
        owner.destroy()
    }

    @Composable
    private fun OverlayContent() {
        // Here rather than in the panel, so minimising to the bubble keeps the recording going.
        AutoRecordEffect(session.note?.id)
        // Derived, so this only reruns when recording starts or stops, not on every level sample.
        val recording by remember { derivedStateOf { voice.isRecording } }
        // The pill widens to fit ✕ and ✓ while recording, then goes back to its own size.
        LaunchedEffect(recording) { update() }
        // The pill records into the open note, so make sure there is one.
        LaunchedEffect(compact) { if (compact) session.ensureOpen() }
        // Content alpha, not window alpha: see the class comment.
        FloatNoteTheme {
            when (mode) {
                OverlayMode.Bubble -> OverlayBubble(
                    voiceState = { voice.state },
                    opacity = opacity,
                    onTap = ::expand,
                    onDragStart = { dragOrigin = bubble },
                    onDrag = { dx, dy -> moveBubble(dx, dy) },
                    onDragEnd = { prefs.bubble = bubble },
                )
                OverlayMode.Panel -> Box(Modifier.fillMaxSize()) {
                    if (compact) {
                        OverlayRecordPill(
                            voiceState = { voice.state },
                            opacity = opacity,
                            onRecord = voiceActions::onMicToggle,
                            onCancel = voiceActions::onCancel,
                            onRestore = ::restoreFullSize,
                            onDragStart = { dragOrigin = panel },
                            onDrag = { dx, dy -> movePanel(dx, dy) },
                            onDragEnd = { prefs.panel = panel },
                        )
                    } else {
                        OverlayPanel(
                            session = session,
                            voiceState = { voice.state },
                            voiceActions = voiceActions,
                            opacity = opacity,
                            panelSize = panelPreset,
                            onPanelSize = ::applyPanelSize,
                            onOpacityChange = ::changeOpacity,
                            onOpacityChangeFinished = { prefs.opacity = opacity },
                            onGeometryStart = { dragOrigin = panel },
                            onMove = { dx, dy -> movePanel(dx, dy) },
                            onGeometryDone = { savePanel() },
                            onMinimise = ::minimise,
                            onClose = onClose,
                        )
                    }
                    // Outside the if/else, so a resize carries on when the content switches
                    // between the note and the pill under the finger. Hidden on the pill
                    // otherwise: it would cover the record button (long-press restores instead).
                    if (!compact || resizing) {
                        ResizeHandle(
                            onResizeStart = {
                                dragOrigin = panel
                                resizing = true
                            },
                            onResize = { dx, dy -> resizePanel(dx, dy) },
                            onResizeEnd = {
                                resizing = false
                                savePanel()
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = opacity },
                        )
                    }
                }
            }
        }
    }

    /** Keeps the top-left corner where it is (pulled back on screen if needed). */
    private fun applyPanelSize(size: PanelSize) {
        panel = panel.copy(width = preset(size.widthDp), height = preset(size.heightDp)).clampedTo(usableArea())
        panelPreset = size
        savePanel()
        update()
    }

    /** From the record pill (long-press): back to the last size that showed the note. */
    private fun restoreFullSize() {
        val full = prefs.fullPanel ?: WindowRect(0, 0, preset(DEFAULT_SIZE.widthDp), preset(DEFAULT_SIZE.heightDp))
        panel = panel.copy(width = full.width, height = full.height).clampedTo(usableArea())
        panelPreset = matchingPreset()
        focusable = true
        savePanel()
        update()
    }

    /** Remembers the panel, and its size as the one to restore if it's still showing the note. */
    private fun savePanel() {
        prefs.panel = panel
        if (!isCompact(panel)) prefs.fullPanel = panel
    }

    private fun changeOpacity(value: Float) {
        opacity = value.coerceIn(OverlayPrefs.MIN_OPACITY, 1f)
    }

    /** Where the window was when the current drag began; drags are offsets from here. */
    private var dragOrigin: WindowRect? = null

    private fun movePanel(dx: Int, dy: Int) {
        val origin = dragOrigin ?: panel
        panel = origin.movedBy(dx, dy).clampedTo(usableArea())
        update()
    }

    private fun resizePanel(dx: Int, dy: Int) {
        val origin = dragOrigin ?: panel
        panel = origin.resizedBy(dx, dy, dp(MIN_PANEL_SIZE_DP), dp(MIN_PANEL_SIZE_DP), usableArea())
        panelPreset = null
        update()
    }

    private fun moveBubble(dx: Int, dy: Int) {
        val origin = dragOrigin ?: bubble
        bubble = origin.movedBy(dx, dy).clampedTo(usableArea())
        update()
    }

    private fun setFocusable(value: Boolean) {
        if (focusable == value) return
        focusable = value
        update()
    }

    private fun update() {
        compact = isCompact(panel)
        if (!attached) return
        applyLayout()
        windowManager.updateViewLayout(root, params)
    }

    private fun applyLayout() {
        val rect = when {
            mode == OverlayMode.Bubble -> bubble
            compact && voice.isRecording ->
                panel.grownTo(dp(PILL_RECORDING_WIDTH_DP), dp(PILL_RECORDING_HEIGHT_DP), usableArea())
            else -> panel
        }
        params.x = rect.x
        params.y = rect.y
        params.width = rect.width
        params.height = rect.height
        // Only the panel needs to hear about outside touches (to hand focus back). For the bubble
        // it would wake the app on every touch anywhere on screen.
        params.flags = if (mode == OverlayMode.Panel) {
            params.flags or FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            params.flags and FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        // The pill has nothing to type into: leave the keyboard and Back with the app underneath.
        val canFocus = mode == OverlayMode.Panel && focusable && !compact
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
        val width = preset(DEFAULT_SIZE.widthDp)
        val height = preset(DEFAULT_SIZE.heightDp)
        val top = area.y + (area.height * 0.08f).toInt()
        // Top right, small: out of the way of whatever app is underneath.
        return WindowRect(area.right - width - dp(12), top, width, height)
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

    /**
     * Preset sizes grow with the system font size (up to 1.5×), so S/M/L still fit their
     * title and chips at large text. The window is still clamped to the screen.
     */
    private fun preset(valueDp: Int): Int =
        (dp(valueDp) * context.resources.configuration.fontScale.coerceIn(1f, MAX_PRESET_SCALE)).toInt()

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    private companion object {
        val DEFAULT_SIZE = PanelSize.Small
        const val MAX_PRESET_SCALE = 1.5f
        /** Below either of these the panel turns into the record pill. */
        const val FULL_MIN_WIDTH_DP = 180
        const val FULL_MIN_HEIGHT_DP = 160

        /** The smallest the corner can make it: one record button. */
        const val MIN_PANEL_SIZE_DP = 44

        /** Room for ✕, a waveform and ✓ while the pill is recording. */
        const val PILL_RECORDING_WIDTH_DP = 136
        const val PILL_RECORDING_HEIGHT_DP = 44
        const val BUBBLE_WIDTH_DP = 56
        const val BUBBLE_HEIGHT_DP = 30
    }
}
