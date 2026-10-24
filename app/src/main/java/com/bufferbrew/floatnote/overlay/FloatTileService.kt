package com.bufferbrew.floatnote.overlay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import com.bufferbrew.floatnote.FloatLaunchActivity

/**
 * Quick Settings tile: opens the floating note. It goes through [FloatLaunchActivity] (a visible
 * Activity) so the foreground service may start with the microphone type, as from the Edge panel.
 */
class FloatTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = FloatLaunchActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
