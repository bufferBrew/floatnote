package com.bufferbrew.floatnote

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.bufferbrew.floatnote.overlay.OverlayPrefs
import com.bufferbrew.floatnote.overlay.openFloatingNote
import com.bufferbrew.floatnote.voice.SharedAudio
import com.bufferbrew.floatnote.voice.hasMicPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "Float note" launcher entry, the one to add to the Samsung Edge panel (or any launcher).
 * The Quick Settings tile, the app shortcuts and "Share to FloatNote" (text, or audio to
 * transcribe) come through here too.
 * It has no UI: it opens the floating window over whatever you were doing and finishes at once.
 * Being a visible Activity for that instant is what lets it start the foreground service,
 * including its microphone type.
 *
 * It also asks for the microphone, and once for notifications (the floating note's Show/Close
 * notification), on the overlay's behalf, since a Service can't show permission prompts. The
 * window is opened after the answers, so the service starts with the mic type when it's allowed.
 */
class FloatLaunchActivity : ComponentActivity() {

    private val app get() = application as FloatNoteApp

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        askNotificationsThenOpen()
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        openAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharing = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
        if (savedInstanceState != null) {
            // Re-created mid prompt: the result callback finishes. A share still copying lost its
            // work with the old instance; finish rather than sit invisible over the screen.
            if (sharing) finish()
            return
        }

        if (sharing) {
            val audio = sharedAudio()
            if (audio.isNotEmpty()) openSharedAudio(audio) else openShared()
            return
        }
        // Static shortcut extras may arrive as a boolean or as the string "true".
        val newNote = intent.getBooleanExtra(EXTRA_NEW_NOTE, false) || intent.getStringExtra(EXTRA_NEW_NOTE) == "true"
        if (newNote || OverlayPrefs(this).openNewNote) {
            app.session.createNote()
        }
        val byUser = openedByUser()
        if (!byUser) app.voice.autoRecordBlockedFor = app.session.note?.id
        // The mic button always asks; auto-record asks only the first time.
        val explicit = byUser && intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)
        val ask = !hasMicPermission() &&
            (explicit || (app.voiceSettings.autoRecord && !askedBefore(KEY_ASKED)))
        if (ask) requestMic.launch(Manifest.permission.RECORD_AUDIO) else askNotificationsThenOpen()
    }

    /** Text shared from another app becomes a new note, shown floating (or in the app if it can't float). */
    private fun openShared() {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        if (text.isNotBlank() || subject.isNotBlank()) app.session.createNote(subject, text)
        // Any app can share to us, so a shared note never starts recording by itself.
        app.voice.autoRecordBlockedFor = app.session.note?.id
        showNoteAndFinish()
    }

    private fun showNoteAndFinish() {
        if (Settings.canDrawOverlays(this)) {
            openFloatingNote()
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    /** Audio files in a share, at most [SharedAudio.MAX_FILES]. Empty for anything else. */
    private fun sharedAudio(): List<Uri> {
        val type = intent.type.orEmpty()
        if (!type.startsWith("audio/") && type != "application/ogg") return emptyList()
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        // Only content another app granted us. A file:// path, or a URI of our own, could make us
        // read (and upload) our own private files on a caller's say-so.
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith(packageName) != true }
            .take(SharedAudio.MAX_FILES)
    }

    /**
     * Shared audio (a voice message, a recording) becomes a new note holding its transcript, sent
     * through the same Whisper upload as a recording. This stays open, invisibly, while it copies
     * the files, because the read access the sharing app granted lasts only as long as it does.
     */
    private fun openSharedAudio(uris: List<Uri>) {
        if (app.voice.isBusy) {
            Toast.makeText(this, R.string.share_audio_busy, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, uri ->
                    val name = displayName(uri)
                    val ext = SharedAudio.extension(name, contentResolver.getType(uri) ?: intent.type)
                    val file = File(cacheDir, "shared-$i.$ext")
                    if (copyShared(uri, file)) name to file else null
                }
            }
            if (copied.isEmpty()) {
                Toast.makeText(this@FloatLaunchActivity, R.string.share_audio_unreadable, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (copied.size < uris.size) {
                Toast.makeText(this@FloatLaunchActivity, R.string.share_audio_some_skipped, Toast.LENGTH_LONG).show()
            }
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            // A title, so the note isn't blank (and deleted on close) before the transcript arrives.
            val title = subject.ifBlank { SharedAudio.title(copied.first().first) ?: getString(R.string.shared_audio_title) }
            app.session.createNote(title, "")
            val noteId = app.session.note?.id
            if (noteId == null) {
                copied.forEach { it.second.delete() }
                finish()
                return@launch
            }
            app.voice.autoRecordBlockedFor = noteId
            val clips = copied.mapIndexed { i, (_, file) ->
                val clip = app.voice.sharedClipFile(noteId, i, file.extension)
                if (file.renameTo(clip)) clip else file
            }
            if (!app.voice.transcribeFiles(clips, noteId)) {
                Toast.makeText(this@FloatLaunchActivity, R.string.share_audio_busy, Toast.LENGTH_LONG).show()
            }
            showNoteAndFinish()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Copies at most [SharedAudio.MAX_BYTES]; false (and nothing left behind) if it can't. */
    private fun copyShared(uri: Uri, to: File): Boolean {
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > SharedAudio.MAX_BYTES) return@use false
                        output.write(buffer, 0, read)
                    }
                    total > 0
                }
            } ?: false
        }.getOrDefault(false)
        if (!ok) to.delete()
        return ok
    }

    /** Android 13+: without this the floating note's notification (Show/Close) never appears. Asked once. */
    private fun askNotificationsThenOpen() {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needed && !askedBefore(KEY_ASKED_NOTIFICATIONS)) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAndFinish()
        }
    }

    private fun openAndFinish() {
        openFloatingNote()
        finish()
    }

    /**
     * Whether the app that started us is one the user drives directly: FloatNote itself (the
     * tile, the mic prompt), a system app (Samsung's Edge panel, SystemUI) or the default home
     * screen (the icon, its shortcuts). This activity is exported, so any other app could open it
     * without the user asking; those opens mustn't start the microphone through auto-record.
     */
    private fun openedByUser(): Boolean {
        // A caller can set these to fake getReferrer(); without them it's the package the system recorded.
        if (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)) return false
        val caller = referrer?.takeIf { it.scheme == "android-app" }?.host ?: return false
        if (caller == packageName) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        if (caller == packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName) {
            return true
        }
        // The caller is visible to us because it started us, so this lookup works on Android 11+.
        return runCatching {
            packageManager.getApplicationInfo(caller, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)
    }

    /** True after the first call for [key]: each prompt is asked at most once from here. */
    private fun askedBefore(key: String): Boolean {
        val prefs = getSharedPreferences("float_launch", Context.MODE_PRIVATE)
        val asked = prefs.getBoolean(key, false)
        if (!asked) prefs.edit().putBoolean(key, true).apply()
        return asked
    }

    companion object {
        private const val EXTRA_REQUEST_MIC = "request_mic"

        /** Set by the "New floating note" shortcut. */
        const val EXTRA_NEW_NOTE = "new_note"
        private const val KEY_ASKED = "asked_mic"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val COPY_BUFFER = 64 * 1024

        /** Asks for the mic, then (re)opens the floating note. Usable from the overlay. */
        fun requestMicIntent(context: Context): Intent =
            Intent(context, FloatLaunchActivity::class.java)
                .putExtra(EXTRA_REQUEST_MIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the floating note, as the launcher entry does. */
        fun intent(context: Context): Intent =
            Intent(context, FloatLaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
