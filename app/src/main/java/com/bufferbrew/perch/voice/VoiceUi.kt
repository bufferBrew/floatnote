package com.bufferbrew.perch.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.bufferbrew.perch.FloatLaunchActivity
import com.bufferbrew.perch.PerchApp
import com.bufferbrew.perch.MainActivity
import com.bufferbrew.perch.R
import com.bufferbrew.perch.ui.components.CircleButton
import com.bufferbrew.perch.ui.theme.Dimens
import kotlin.math.sqrt

/** What the editor's voice controls can ask for. */
interface VoiceActions {
    fun onMicToggle()
    fun onPauseToggle()
    fun onRetry()
    fun onCancel()
    fun onOpenSettings()
    fun onDismiss()
}

fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The real [VoiceActions], used by both hosts. Anything that needs an Activity (asking for the
 * mic permission, the settings screen) is routed through one, since the overlay has none.
 * [beforeOpeningScreen] runs first: the overlay minimises there, because activities (even the
 * permission prompt) draw underneath overlay windows.
 */
class AppVoiceActions(
    private val context: Context,
    private val beforeOpeningScreen: () -> Unit = {},
    /** How to ask for the mic; by default through FloatLaunchActivity (the overlay has no Activity). */
    private val requestMic: (() -> Unit)? = null,
) : VoiceActions {
    private val app get() = context.applicationContext as PerchApp

    override fun onMicToggle() {
        val noteId = app.session.note?.id ?: return
        // Wispr Flow: get a line ready to type on; the keyboard (and Wispr's bubble) follow.
        // Anything already recording (from before the switch) is still stopped the usual way.
        if (app.voiceSettings.engine == VoiceEngine.Wispr && app.voice.state !is VoiceState.Recording) {
            app.session.focusEnd()
            return
        }
        // Not set up yet (Whisper without a key): toggle() explains; no point asking for the mic.
        if (app.voiceSettings.isReady && !context.hasMicPermission()) {
            requestMic?.let { it(); return }
            beforeOpeningScreen()
            context.startActivity(FloatLaunchActivity.requestMicIntent(context))
            return
        }
        app.voice.toggle(noteId)
    }

    override fun onPauseToggle() = app.voice.togglePause()

    override fun onRetry() = app.voice.retry()

    override fun onCancel() = app.voice.cancel()

    override fun onOpenSettings() {
        beforeOpeningScreen()
        context.startActivity(MainActivity.settingsIntent(context))
    }

    override fun onDismiss() = app.voice.dismiss()
}

/**
 * Starts recording when a note opens, if auto-record is on (off by default) and voice is set up, and stops
 * (sending the clip) when that note closes or this host goes away. Both hosts use it; starting
 * twice for the same note is a no-op.
 */
@Composable
fun AutoRecordEffect(noteId: String?) {
    val context = LocalContext.current
    val app = context.applicationContext as PerchApp
    LaunchedEffect(noteId) {
        if (noteId != app.voice.autoRecordBlockedFor) app.voice.autoRecordBlockedFor = null
        if (noteId != null && noteId != app.voice.autoRecordBlockedFor &&
            app.voiceSettings.autoRecord && app.voiceSettings.isReady &&
            context.hasMicPermission()
        ) {
            app.voice.start(noteId)
        }
    }
    // Leaving the note (back to the list, or another note) ends the clip and sends it. A host
    // merely going away doesn't: the other host may still be showing this note and recording.
    // Hosts handle their own exit (MainActivity.onStop, OverlayWindow.destroy).
    DisposableEffect(noteId) {
        onDispose { if (noteId != null && app.session.note?.id != noteId) app.voice.stop() }
    }
}

/**
 * Status strip above the toolbar: live waveform while recording, progress while
 * transcribing, and errors with what to do next. Nothing when idle.
 */
@Composable
fun VoiceStatus(
    voiceState: () -> VoiceState,
    actions: VoiceActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // The Canvas observes audio samples. Layout only observes visible status and whole seconds.
    val state by remember(voiceState) {
        derivedStateOf(structuralEqualityPolicy()) {
            when (val current = voiceState()) {
                is VoiceState.Recording ->
                    current.copy(levels = emptyList(), elapsedMs = current.elapsedMs / 1000 * 1000)
                else -> current
            }
        }
    }
    if (state == VoiceState.Idle) return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(colors.surfaceVariant)
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
    ) {
        when (val current = state) {
            is VoiceState.Recording -> {
                Text(
                    stringResource(
                        when {
                            current.paused -> R.string.voice_paused
                            current.fallback -> R.string.voice_listening_phone
                            else -> R.string.voice_listening
                        },
                        remember(current.elapsedMs) { formatElapsed(current.elapsedMs) },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleButton("✕", stringResource(R.string.voice_discard), actions::onCancel, dense = compact)
                    Waveform(
                        levelAt = { index -> (voiceState() as? VoiceState.Recording)?.levels.sampleAt(index) },
                        color = colors.primary,
                        maxHeight = Dimens.WaveMaxHeight,
                        modifier = Modifier.weight(1f).padding(horizontal = Dimens.SpaceXs),
                    )
                    if (current.canPause) {
                        CircleButton(
                            if (current.paused) "▶" else "❚❚",
                            stringResource(if (current.paused) R.string.voice_resume else R.string.voice_pause),
                            actions::onPauseToggle,
                            dense = compact,
                        )
                    }
                    CircleButton("✓", stringResource(R.string.voice_done), actions::onMicToggle, dense = compact)
                }
                if (current.partial.isNotBlank()) {
                    Text(
                        current.partial,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                    )
                }
            }
            VoiceState.Transcribing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    stringResource(R.string.voice_transcribing),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Always a way out, so the panel never looks stuck on a slow connection.
                TextButton(onClick = actions::onCancel) { Text(stringResource(R.string.cancel)) }
            }
            is VoiceState.Error -> {
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (current.needsSettings) {
                        TextButton(onClick = actions::onOpenSettings) { Text(stringResource(R.string.settings)) }
                    }
                    if (current.canRetry) {
                        TextButton(onClick = actions::onRetry) { Text(stringResource(R.string.voice_retry)) }
                    }
                    TextButton(onClick = actions::onDismiss) { Text(stringResource(R.string.voice_dismiss)) }
                }
            }
            VoiceState.Idle -> Unit
        }
    }
}

/**
 * Bars that follow recent loudness, newest on the right. Missing samples (just started)
 * show as the quietest bar, so the shape grows in from the right.
 *
 * Drawn in one Canvas pass with no per-bar animation: it only redraws when a new sample
 * arrives (~10×/s). Animating each bar kept the overlay rendering at 60 fps and cost ~40% of a
 * CPU core while listening, which made the floating panel sluggish.
 */
@Composable
fun Waveform(
    levelAt: (Int) -> Float,
    color: Color,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    bars: Int = VoiceController.MAX_LEVELS,
) {
    val label = stringResource(R.string.voice_waveform)
    val barWidth = Dimens.WaveBarWidth
    Canvas(
        modifier = modifier
            .width(barWidth * (2 * bars - 1))
            .height(maxHeight)
            .semantics { contentDescription = label },
    ) {
        val w = minOf(barWidth.toPx(), size.width / (2 * bars - 1), size.height)
        val start = (size.width - (2 * bars - 1) * w) / 2
        for (i in 0 until bars) {
            val level = levelAt(i)
            // Speech amplitudes are mostly small; a square root lifts quiet talking into view.
            val h = w + (size.height - w) * sqrt(level.coerceIn(0f, 1f))
            drawRoundRect(
                color = color,
                topLeft = Offset(start + i * 2 * w, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/** An index into a right-aligned window of recent samples. */
internal fun List<Float>?.sampleAt(index: Int, bars: Int = VoiceController.MAX_LEVELS): Float =
    this?.getOrNull(size - bars + index) ?: 0f

/** m:ss, for the recording time. */
fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
