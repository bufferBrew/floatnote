package com.bufferbrew.floatnote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.bufferbrew.floatnote.overlay.openFloatingNote
import com.bufferbrew.floatnote.voice.hasMicPermission

/**
 * The "Float note" launcher entry, the one to add to the Samsung Edge panel (or any launcher).
 * It has no UI: it opens the floating window over whatever you were doing and finishes at once.
 * Being a visible Activity for that instant is what lets it start the foreground service,
 * including its microphone type.
 *
 * It also asks for the microphone on the overlay's behalf, since a Service can't show the
 * permission prompt. The window is opened after the answer, so the service starts with the
 * mic type when it's allowed.
 */
class FloatLaunchActivity : ComponentActivity() {

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        openAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // re-created mid prompt; the result callback finishes
        // The mic button always asks; auto-record asks only the first time.
        val explicit = intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)
        val ask = !hasMicPermission() &&
            (explicit || ((application as FloatNoteApp).voiceSettings.autoRecord && !askedBefore()))
        if (ask) requestMic.launch(Manifest.permission.RECORD_AUDIO) else openAndFinish()
    }

    private fun openAndFinish() {
        openFloatingNote()
        finish()
    }

    /** True after the first call: auto-record asks at most once from here. */
    private fun askedBefore(): Boolean {
        val prefs = getSharedPreferences("float_launch", Context.MODE_PRIVATE)
        val asked = prefs.getBoolean(KEY_ASKED, false)
        if (!asked) prefs.edit().putBoolean(KEY_ASKED, true).apply()
        return asked
    }

    companion object {
        private const val EXTRA_REQUEST_MIC = "request_mic"
        private const val KEY_ASKED = "asked_mic"

        /** Asks for the mic, then (re)opens the floating note. Usable from the overlay. */
        fun requestMicIntent(context: Context): Intent =
            Intent(context, FloatLaunchActivity::class.java)
                .putExtra(EXTRA_REQUEST_MIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
