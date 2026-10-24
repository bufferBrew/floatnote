package com.bufferbrew.floatnote

import android.app.Activity
import android.os.Bundle
import com.bufferbrew.floatnote.overlay.openFloatingNote

/**
 * The "Float note" launcher entry, the one to add to the Samsung Edge panel (or any launcher).
 * It has no UI: it opens the floating window over whatever you were doing and finishes at once.
 * Being a visible Activity for that instant is what lets it start the foreground service.
 */
class FloatLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openFloatingNote()
        finish()
    }
}
