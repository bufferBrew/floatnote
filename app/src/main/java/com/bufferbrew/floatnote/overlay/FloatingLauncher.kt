package com.bufferbrew.floatnote.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.bufferbrew.floatnote.R

/**
 * Opens the floating note, or, if "Display over other apps" isn't allowed yet, sends the user
 * to that setting. Returns true if the window is opening.
 */
fun Context.openFloatingNote(): Boolean {
    if (Settings.canDrawOverlays(this)) {
        OverlayService.show(this)
        return true
    }
    Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
    startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    return false
}
