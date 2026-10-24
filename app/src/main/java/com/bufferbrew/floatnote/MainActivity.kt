package com.bufferbrew.floatnote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bufferbrew.floatnote.overlay.OverlayPrefs
import com.bufferbrew.floatnote.overlay.OverlayService
import com.bufferbrew.floatnote.overlay.openFloatingNote
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.editor.NoteEditorContent
import com.bufferbrew.floatnote.ui.editor.shareNote
import com.bufferbrew.floatnote.ui.history.HistoryContent
import com.bufferbrew.floatnote.ui.list.NoteListContent
import com.bufferbrew.floatnote.ui.settings.SettingsContent
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.ui.theme.FloatNoteTheme
import com.bufferbrew.floatnote.voice.AppVoiceActions
import com.bufferbrew.floatnote.voice.AutoRecordEffect
import com.bufferbrew.floatnote.voice.hasMicPermission
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as FloatNoteApp
    private val session get() = app.session

    private var showSettings by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    private val overlayPrefs by lazy { OverlayPrefs(this) }
    private var openNewNote by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)

    /** Asked once, before the first float, so the floating note's Show/Close notification appears. */
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        floatNow()
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        micGranted = hasMicPermission()
        openNewNote = overlayPrefs.openNewNote
        showSettings = intent.getBooleanExtra(EXTRA_SETTINGS, false)
        askForMicOnce()

        setContent {
            // Ask for the mic here, not through FloatLaunchActivity, which would float the note.
            val voiceActions = remember {
                AppVoiceActions(this, requestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) })
            }
            FloatNoteTheme {
                Surface(Modifier.fillMaxSize()) {
                    val contentModifier = Modifier
                        .safeDrawingPadding()
                        .imePadding()
                        .padding(horizontal = Dimens.SpaceM)
                    val note = session.note
                    AutoRecordEffect(note?.id)
                    when {
                        showSettings -> {
                            BackHandler { showSettings = false }
                            val vocabulary by app.vocabulary.collectAsState()
                            SettingsContent(
                                settings = app.voiceSettings,
                                micGranted = micGranted,
                                onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                                vocabulary = vocabulary,
                                onAddVocab = { term, replacement ->
                                    app.persistScope.launch { app.voiceRepository.addVocab(term, replacement) }
                                },
                                onDeleteVocab = { id -> app.persistScope.launch { app.voiceRepository.deleteVocab(id) } },
                                openNewNote = openNewNote,
                                onOpenNewNoteChange = {
                                    openNewNote = it
                                    overlayPrefs.openNewNote = it
                                },
                                onBack = { showSettings = false },
                                modifier = contentModifier,
                            )
                        }
                        showHistory && note == null -> {
                            BackHandler { showHistory = false }
                            val history by app.voiceRepository.observeHistory().collectAsState(initial = emptyList())
                            Column(contentModifier) {
                                TextButton(onClick = { showHistory = false }) {
                                    Text(
                                        stringResource(R.string.back_to_notes),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HistoryContent(
                                    entries = history,
                                    onOpenNote = {
                                        showHistory = false
                                        session.open(it)
                                    },
                                    onDelete = { id -> app.persistScope.launch { app.voiceRepository.deleteDictation(id) } },
                                    onClear = { app.persistScope.launch { app.voiceRepository.clearHistory() } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        note == null -> {
                            val notes by session.notes.collectAsState(initial = emptyList())
                            Column(contentModifier) {
                                Row(
                                    Modifier.padding(top = Dimens.SpaceS),
                                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    PillButton(stringResource(R.string.settings), { showSettings = true })
                                    FloatButton()
                                }
                                NoteListContent(
                                    notes = notes,
                                    onOpen = session::open,
                                    onCreate = session::createNote,
                                    onDelete = session::deleteNote,
                                    modifier = Modifier.weight(1f),
                                    onHistory = { showHistory = true },
                                    deletedTitle = session.recentlyDeleted?.title,
                                    onUndoDelete = session::undoDelete,
                                    onDeletedDismissed = session::clearDeleted,
                                )
                            }
                        }
                        else -> {
                            BackHandler { session.closeNote() }
                            Column(contentModifier) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = session::closeNote) {
                                        Text(
                                            stringResource(R.string.back_to_notes),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    FloatButton()
                                }
                                NoteEditorContent(
                                    note = note,
                                    pendingFocus = session.pendingFocus,
                                    actions = session,
                                    voiceState = { app.voice.state },
                                    voiceActions = voiceActions,
                                    modifier = Modifier.weight(1f),
                                    assist = session.assist,
                                    onShare = { shareNote(note) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SETTINGS, false)) showSettings = true
    }

    override fun onResume() {
        super.onResume()
        micGranted = hasMicPermission()
    }

    /** Hands the open note to the floating window and steps out of the way. */
    @Composable
    private fun FloatButton() {
        PillButton(
            text = stringResource(R.string.float_button),
            onClick = ::float,
            selected = true,
        )
    }

    private fun float() {
        val prefs = getSharedPreferences("main", Context.MODE_PRIVATE)
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needed && !prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            floatNow()
        }
    }

    private fun floatNow() {
        if (openFloatingNote()) moveTaskToBack(true)
    }

    /** With auto-record on, ask for the mic up front, but only the first time. */
    private fun askForMicOnce() {
        val prefs = getSharedPreferences("main", Context.MODE_PRIVATE)
        if (!app.voiceSettings.autoRecord || micGranted || prefs.getBoolean(KEY_ASKED_MIC, false)) return
        prefs.edit().putBoolean(KEY_ASKED_MIC, true).apply()
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        super.onStop()
        // In the background only the overlay's foreground service may use the mic, so without
        // it the recording would go silent: finish and send it now instead.
        if (!OverlayService.isRunning) app.voice.stop()
        // The process can be killed any time after this, so don't wait for the save debounce.
        session.flush()
    }

    companion object {
        private const val EXTRA_SETTINGS = "open_settings"
        private const val KEY_ASKED_MIC = "asked_mic"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

        fun settingsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
