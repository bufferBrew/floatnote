package com.bufferbrew.floatnote.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.bufferbrew.floatnote.FloatLaunchActivity
import com.bufferbrew.floatnote.FloatNoteApp
import com.bufferbrew.floatnote.MainActivity
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.ui.components.CircleButton
import com.bufferbrew.floatnote.ui.theme.Dimens
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
    private val app get() = context.applicationContext as FloatNoteApp

    override fun onMicToggle() {
        val noteId = app.session.note?.id ?: return
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
    val app = context.applicationContext as FloatNoteApp
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
fun VoiceStatus(voiceState: () -> VoiceState, actions: VoiceActions, modifier: Modifier = Modifier) {
    val state = voiceState()
    if (state == VoiceState.Idle) return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(colors.surfaceVariant)
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
    ) {
        when (state) {
            is VoiceState.Recording -> {
                // Like Wispr Flow: ✕ throws the recording away, ✓ stops and adds it to the note.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleButton("✕", stringResource(R.string.voice_discard), actions::onCancel, dense = true)
                    Waveform(
                        levels = state.levels,
                        color = colors.onSurface,
                        maxHeight = Dimens.WaveMaxHeight,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(
                            when {
                                state.paused -> R.string.voice_paused
                                state.fallback -> R.string.voice_listening_phone
                                else -> R.string.voice_listening
                            },
                            formatElapsed(state.elapsedMs),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = Dimens.SpaceS),
                    )
                    // Whisper clips only: the device recognizer can't pause mid-dictation.
                    if (state.canPause) {
                        CircleButton(
                            if (state.paused) "▶" else "❚❚",
                            stringResource(if (state.paused) R.string.voice_resume else R.string.voice_pause),
                            actions::onPauseToggle,
                            dense = true,
                        )
                    }
                    CircleButton("✓", stringResource(R.string.voice_done), actions::onMicToggle, dense = true)
                }
                // Device engine: the sentence being heard, before it lands in the note.
                if (state.partial.isNotBlank()) {
                    Text(
                        state.partial,
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
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (state.needsSettings) {
                        TextButton(onClick = actions::onOpenSettings) { Text(stringResource(R.string.settings)) }
                    }
                    if (state.canRetry) {
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
    levels: List<Float>,
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
        val w = barWidth.toPx()
        val minH = w
        val padded = List((bars - levels.size).coerceAtLeast(0)) { 0f } + levels.takeLast(bars)
        padded.forEachIndexed { i, level ->
            // Speech amplitudes are mostly small; a square root lifts quiet talking into view.
            val h = minH + (size.height - minH) * sqrt(level.coerceIn(0f, 1f))
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2 * w, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/** m:ss, for the recording time. */
fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
