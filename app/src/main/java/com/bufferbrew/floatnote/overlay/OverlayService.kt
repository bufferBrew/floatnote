package com.bufferbrew.floatnote.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bufferbrew.floatnote.FloatNoteApp
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.voice.VoiceState
import com.bufferbrew.floatnote.voice.formatElapsed
import com.bufferbrew.floatnote.voice.hasMicPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns the floating window. Runs in the foreground so Android doesn't kill the note you're
 * writing while another app is in front; the notification also offers Show and Close, and
 * while a recording runs, its time and Pause / Resume / Stop.
 */
class OverlayService : Service() {

    private var window: OverlayWindow? = null

    private val app get() = application as FloatNoteApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the notification shows. Changes only when a recording starts, pauses or ends, not with each level sample. */
    private enum class Phase { Idle, Recording, Paused, Transcribing }

    private fun phase(state: VoiceState) = when (state) {
        is VoiceState.Recording -> if (state.paused) Phase.Paused else Phase.Recording
        VoiceState.Transcribing -> Phase.Transcribing
        else -> Phase.Idle
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must come first after startForegroundService(), even if we stop right away.
        startInForeground()

        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action in RECORDING_ACTIONS) {
            // A notification button: act on the recording, leave the window as it is.
            if (window == null) stopSelf()
            when (intent?.action) {
                ACTION_PAUSE -> app.voice.pause()
                ACTION_RESUME -> app.voice.resume()
                ACTION_STOP_RECORDING -> app.voice.stop()
            }
            return START_NOT_STICKY
        }
        val current = window ?: OverlayWindow(
            context = ContextThemeWrapper(overlayContext(), R.style.Theme_FloatNote),
            session = app.session,
            voice = app.voice,
            prefs = OverlayPrefs(this),
            onClose = ::stopSelf,
        ).also { window = it }
        current.show()
        // Not sticky: after a crash or kill, don't pop a window up unasked.
        return START_NOT_STICKY
    }

    /**
     * A window context (Android 11+): the right base for a window that isn't an Activity. Its
     * metrics follow the display it's on (fold/unfold, resolution changes), which a plain
     * Service context doesn't guarantee, and it doesn't trip StrictMode's incorrect-context check.
     */
    private fun overlayContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return this
        return createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.onScreenChanged()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        scope.launch {
            snapshotFlow { phase(app.voice.state) }
                .distinctUntilChanged()
                // Shown without asking again if notifications are off: it then just doesn't appear.
                .collect { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        window?.destroy()
        window = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        // The microphone type is what lets voice notes keep recording while another app is in
        // front. Declaring it without the permission throws, so add it only when granted.
        // Started from a visible Activity, it keeps mic access for the service's lifetime.
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notification(): Notification {
        val show = PendingIntent.getService(
            this, 0, intent(this, ACTION_SHOW), PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this, 1, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(show)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        when (val state = app.voice.state) {
            is VoiceState.Recording -> {
                if (state.paused) {
                    builder.setContentTitle(getString(R.string.notification_paused, formatElapsed(state.elapsedMs)))
                        .addAction(0, getString(R.string.notification_resume), action(ACTION_RESUME, 2))
                } else {
                    // The system counts up from here, so the notification isn't reposted every second.
                    builder.setContentTitle(getString(R.string.notification_recording))
                        .setUsesChronometer(true)
                        .setShowWhen(true)
                        .setWhen(System.currentTimeMillis() - state.elapsedMs)
                    if (state.canPause) builder.addAction(0, getString(R.string.notification_pause), action(ACTION_PAUSE, 2))
                }
                builder.addAction(0, getString(R.string.notification_stop), action(ACTION_STOP_RECORDING, 3))
            }
            VoiceState.Transcribing -> builder.setContentTitle(getString(R.string.voice_transcribing))
            else -> Unit
        }
        return builder.addAction(0, getString(R.string.close_floating), close).build()
    }

    private fun action(action: String, requestCode: Int) =
        PendingIntent.getService(this, requestCode, intent(this, action), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        /** True while the floating window's service is alive (same process, main thread). */
        var isRunning = false
            private set

        private const val ACTION_SHOW = "com.bufferbrew.floatnote.overlay.SHOW"
        private const val ACTION_STOP = "com.bufferbrew.floatnote.overlay.STOP"
        private const val ACTION_PAUSE = "com.bufferbrew.floatnote.overlay.PAUSE_RECORDING"
        private const val ACTION_RESUME = "com.bufferbrew.floatnote.overlay.RESUME_RECORDING"
        private const val ACTION_STOP_RECORDING = "com.bufferbrew.floatnote.overlay.STOP_RECORDING"
        private val RECORDING_ACTIONS = setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_STOP_RECORDING)
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        private fun intent(context: Context, action: String) =
            Intent(context, OverlayService::class.java).setAction(action)

        /** Shows the floating note. Call from a visible screen (see CLAUDE.md gotchas). */
        fun show(context: Context) {
            context.startForegroundService(intent(context, ACTION_SHOW))
        }
    }
}
