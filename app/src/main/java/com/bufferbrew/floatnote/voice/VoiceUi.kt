package com.bufferbrew.floatnote.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.bufferbrew.floatnote.FloatLaunchActivity
import com.bufferbrew.floatnote.FloatNoteApp
import com.bufferbrew.floatnote.MainActivity
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.ui.theme.Dimens

/** What the editor's voice controls can ask for. */
interface VoiceActions {
    fun onMicToggle()
    fun onRetry()
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
) : VoiceActions {
    private val app get() = context.applicationContext as FloatNoteApp

    override fun onMicToggle() {
        val noteId = app.session.note?.id ?: return
        // Without a key, toggle() explains that first; no point asking for the mic yet.
        if (app.voiceSettings.hasApiKey && !context.hasMicPermission()) {
            beforeOpeningScreen()
            context.startActivity(FloatLaunchActivity.requestMicIntent(context))
            return
        }
        app.voice.toggle(noteId)
    }

    override fun onRetry() = app.voice.retry()

    override fun onOpenSettings() {
        beforeOpeningScreen()
        context.startActivity(MainActivity.settingsIntent(context))
    }

    override fun onDismiss() = app.voice.dismiss()
}

/**
 * Starts recording when a note opens, if auto-record is on and voice is set up, and stops
 * (sending the clip) when that note closes or this host goes away. Both hosts use it; starting
 * twice for the same note is a no-op.
 */
@Composable
fun AutoRecordEffect(noteId: String?) {
    val context = LocalContext.current
    val app = context.applicationContext as FloatNoteApp
    LaunchedEffect(noteId) {
        if (noteId != null && app.voiceSettings.autoRecord && app.voiceSettings.hasApiKey &&
            context.hasMicPermission()
        ) {
            app.voice.start(noteId)
        }
    }
    // Leaving the note (or closing this host) ends the clip and sends it to that note.
    DisposableEffect(noteId) {
        onDispose { if (noteId != null) app.voice.stop() }
    }
}

/**
 * Status strip above the toolbar: live waveform while recording, progress while
 * transcribing, and errors with what to do next. Nothing when idle.
 */
@Composable
fun VoiceStatus(state: VoiceState, actions: VoiceActions, modifier: Modifier = Modifier) {
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
            is VoiceState.Recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform(
                    levels = state.levels,
                    color = colors.onSurface,
                    maxHeight = Dimens.WaveMaxHeight,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.voice_listening, formatElapsed(state.elapsedMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = Dimens.SpaceS),
                )
            }
            VoiceState.Transcribing -> Text(
                stringResource(R.string.voice_transcribing),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Dimens.SpaceXs),
            )
            is VoiceState.Error -> {
                Text(state.message, style = MaterialTheme.typography.bodySmall)
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
    Row(
        modifier = modifier.height(maxHeight).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(Dimens.WaveBarWidth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val padded = List((bars - levels.size).coerceAtLeast(0)) { 0f } + levels.takeLast(bars)
        padded.forEach { level -> WaveBar(level, color, maxHeight) }
    }
}

@Composable
fun WaveBar(level: Float, color: Color, maxHeight: Dp) {
    val minHeight = Dimens.WaveBarWidth
    // Speech amplitudes are mostly small; a square root lifts quiet talking into view.
    val target = minHeight + (maxHeight - minHeight) * kotlin.math.sqrt(level.coerceIn(0f, 1f))
    val height by animateDpAsState(target, label = "waveBar")
    Box(
        Modifier
            .width(Dimens.WaveBarWidth)
            .height(height)
            .clip(CircleShape)
            .background(color)
    )
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
