package com.bufferbrew.perch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bufferbrew.perch.overlay.OverlayPrefs
import com.bufferbrew.perch.overlay.OverlayService
import com.bufferbrew.perch.overlay.openFloatingNote
import com.bufferbrew.perch.ui.components.PillButton
import com.bufferbrew.perch.ui.components.ScreenSwitcher
import com.bufferbrew.perch.ui.components.rememberLast
import com.bufferbrew.perch.domain.model.NoteSummary
import com.bufferbrew.perch.ui.editor.NoteEditorContent
import com.bufferbrew.perch.ui.editor.shareNote
import com.bufferbrew.perch.ui.dictionary.DictionaryContent
import com.bufferbrew.perch.ui.history.HistoryContent
import com.bufferbrew.perch.ui.list.NoteListContent
import com.bufferbrew.perch.ui.settings.SettingsContent
import com.bufferbrew.perch.ui.theme.Dimens
import com.bufferbrew.perch.ui.theme.PerchTheme
import com.bufferbrew.perch.voice.AppVoiceActions
import com.bufferbrew.perch.voice.AutoRecordEffect
import com.bufferbrew.perch.voice.hasMicPermission
import androidx.lifecycle.lifecycleScope
import com.bufferbrew.perch.data.NotesBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app get() = application as PerchApp
    private val session get() = app.session

    private var showSettings by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    private var showDictionary by mutableStateOf(false)
    private val overlayPrefs by lazy { OverlayPrefs(this) }
    private var openNewNote by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var backupMessage by mutableStateOf<String?>(null)

    private val exportNotes = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runBackup { NotesBackup.export(this, app, uri) }
    }

    private val importNotes = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runBackup { NotesBackup.import(this, app, uri) }
    }

    /** The Markdown folder's name on the Backup page; null while exporting is off. */
    private var markdownFolder by mutableStateOf<String?>(null)

    private val chooseMarkdownFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) setMarkdownFolder(tree)
    }

    private fun setMarkdownFolder(tree: Uri?) {
        runBackup {
            app.setMarkdownFolder(tree)
            markdownFolder = withContext(Dispatchers.IO) { app.markdownExport.folderName() }
            getString(if (tree == null) R.string.markdown_stopped else R.string.markdown_done)
        }
    }

    /** Asked once, before the first float, so the floating note's Show/Close notification appears. */
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        floatNow()
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        micGranted = hasMicPermission()
        openNewNote = overlayPrefs.openNewNote
        showSettings = intent.getBooleanExtra(EXTRA_SETTINGS, false)
        askForMicOnce()
        lifecycleScope.launch { markdownFolder = withContext(Dispatchers.IO) { app.markdownExport.folderName() } }

        setContent {
            // Ask for the mic here, not through FloatLaunchActivity, which would float the note.
            val voiceActions = remember {
                AppVoiceActions(this, requestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) })
            }
            val palette by app.palette.collectAsState()
            // Material 3 margins: 16 dp on phones, 24 dp from medium width up. Columns and the
            // reading width are worked out from the content's own slot, not from this.
            val gutter = if (calculateWindowSizeClass(this).widthSizeClass == WindowWidthSizeClass.Compact) {
                Dimens.SpaceM
            } else {
                Dimens.GutterWide
            }
            PerchTheme(palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val note = session.note
                    AutoRecordEffect(note?.id)
                    val stateHolder = rememberSaveableStateHolder()
                    ScreenSwitcher(
                        screen = currentScreen(),
                        depth = { if (it == AppScreen.Notes) 0 else 1 },
                        backTo = AppScreen.Notes.takeIf { currentScreen() != AppScreen.Notes },
                        onBack = ::goBack,
                        contentAlignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                    ) { screen ->
                        val contentModifier = Modifier
                            .widthIn(max = Dimens.ReadingWidth)
                            .fillMaxSize()
                            .padding(horizontal = gutter)
                        when (screen) {
                            AppScreen.Settings -> SettingsContent(
                                settings = app.voiceSettings,
                                categories = app.categoryContext,
                                connectionCheck = app.connectionCheck,
                                recordings = app.recordings,
                                micGranted = micGranted,
                                onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                                openNewNote = openNewNote,
                                onOpenNewNoteChange = {
                                    openNewNote = it
                                    overlayPrefs.openNewNote = it
                                },
                                palette = palette,
                                onPaletteChange = app::setPalette,
                                onExport = { exportNotes.launch("perch-backup.json") },
                                onImport = { importNotes.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                backupMessage = backupMessage,
                                markdownFolder = markdownFolder,
                                onChooseMarkdownFolder = { chooseMarkdownFolder.launch(null) },
                                onStopMarkdown = { setMarkdownFolder(null) },
                                onBack = ::goBack,
                                modifier = contentModifier,
                            )
                            AppScreen.Dictionary -> Column(contentModifier) {
                                BackToNotes(::goBack)
                                val vocabulary by app.vocabulary.collectAsState()
                                DictionaryContent(
                                    entries = vocabulary,
                                    onAdd = { term, replacement ->
                                        app.persistScope.launch { app.voiceRepository.addVocab(term, replacement) }
                                    },
                                    onDelete = { id -> app.persistScope.launch { app.voiceRepository.deleteVocab(id) } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            AppScreen.History -> Column(contentModifier) {
                                BackToNotes(::goBack)
                                val history by app.voiceRepository.observeHistory().collectAsState(initial = emptyList())
                                HistoryContent(
                                    entries = history,
                                    onOpenNote = {
                                        showHistory = false
                                        session.open(it)
                                    },
                                    onDelete = { id -> app.persistScope.launch { app.voiceRepository.deleteDictation(id) } },
                                    onClear = { app.persistScope.launch { app.voiceRepository.clearHistory() } },
                                    onUseOriginal = { session.useOriginal(it.noteId, it.text, it.raw ?: it.text) },
                                    audioOf = { app.recordings.file(it.audio) },
                                    onTranscribeAgain = { app.transcribeAgainOrSay(it) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            AppScreen.Notes -> stateHolder.SaveableStateProvider("notes") {
                                val notes by session.searchResults.collectAsState(initial = null as List<NoteSummary>?)
                                NoteListContent(
                                    notes = notes,
                                    onOpen = session::open,
                                    onCreate = session::createNote,
                                    onDelete = session::deleteNote,
                                    modifier = contentModifier,
                                    onHistory = { showHistory = true },
                                    onDictionary = { showDictionary = true },
                                    onSettings = { showSettings = true },
                                    onFloat = ::float,
                                    deletedTitle = session.recentlyDeleted?.title,
                                    onUndoDelete = session::undoDelete,
                                    onDeletedDismissed = session::clearDeleted,
                                    query = session.query,
                                    onQueryChange = { session.query = it },
                                    onPin = session::setPinned,
                                    categoryFilter = session.categoryFilter,
                                    onCategoryFilter = { session.categoryFilter = it },
                                )
                            }
                            is AppScreen.Editor -> {
                                // Still drawn for a moment after closing, while it slides out.
                                val shown = rememberLast(session.note?.takeIf { it.id == screen.noteId }) ?: return@ScreenSwitcher
                                Column(contentModifier) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        BackToNotes(::goBack)
                                        Spacer(Modifier.weight(1f))
                                        FloatButton()
                                    }
                                    NoteEditorContent(
                                        note = shown,
                                        pendingFocus = session.pendingFocus,
                                        actions = session,
                                        voiceState = { app.voice.state },
                                        voiceActions = voiceActions,
                                        modifier = Modifier.weight(1f),
                                        assist = session.assist,
                                        canUndo = session.canUndo,
                                        canRedo = session.canRedo,
                                        onShare = { shareNote(shown) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Which screen the app state points at. Settings wins, then an open note. */
    private fun currentScreen(): AppScreen {
        val note = session.note
        return when {
            showSettings -> AppScreen.Settings
            note != null -> AppScreen.Editor(note.id)
            showDictionary -> AppScreen.Dictionary
            showHistory -> AppScreen.History
            else -> AppScreen.Notes
        }
    }

    /** One level up, to the notes list. The same for ‹ Notes and the system back gesture. */
    private fun goBack() {
        when (currentScreen()) {
            AppScreen.Settings -> {
                showSettings = false
                backupMessage = null
            }
            is AppScreen.Editor -> session.closeNote()
            AppScreen.Dictionary -> showDictionary = false
            AppScreen.History -> showHistory = false
            AppScreen.Notes -> Unit
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

    /** Export/import off the main thread; the result line shows on the Backup page. */
    private fun runBackup(work: suspend () -> String) {
        backupMessage = getString(R.string.backup_working)
        lifecycleScope.launch {
            backupMessage = try {
                work()
            } catch (e: Exception) {
                getString(R.string.backup_failed, e.message ?: e.javaClass.simpleName)
            }
        }
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

/** The full app's screens. Keys only: each screen reads its own live data. */
private sealed interface AppScreen {
    data object Notes : AppScreen
    data object Settings : AppScreen
    data object History : AppScreen
    data object Dictionary : AppScreen
    data class Editor(val noteId: String) : AppScreen
}

@Composable
private fun BackToNotes(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.back_to_notes), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
