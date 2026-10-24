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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bufferbrew.floatnote.FloatNoteApp
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.voice.hasMicPermission

/**
 * Owns the floating window. Runs in the foreground so Android doesn't kill the note you're
 * writing while another app is in front; the notification also offers Show and Close.
 */
class OverlayService : Service() {

    private var window: OverlayWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must come first after startForegroundService(), even if we stop right away.
        startInForeground()

        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as FloatNoteApp
        val current = window ?: OverlayWindow(
            context = ContextThemeWrapper(this, R.style.Theme_FloatNote),
            session = app.session,
            voice = app.voice,
            prefs = OverlayPrefs(this),
            onClose = ::stopSelf,
        ).also { window = it }
        current.show()
        // Not sticky: after a crash or kill, don't pop a window up unasked.
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.onScreenChanged()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(show)
            .addAction(0, getString(R.string.close_floating), close)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        /** True while the floating window's service is alive (same process, main thread). */
        var isRunning = false
            private set

        private const val ACTION_SHOW = "com.bufferbrew.floatnote.overlay.SHOW"
        private const val ACTION_STOP = "com.bufferbrew.floatnote.overlay.STOP"
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
