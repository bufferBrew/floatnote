package com.bufferbrew.floatnote

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import android.net.NetworkCapabilities
import android.widget.Toast
import com.bufferbrew.floatnote.ai.CategoryContext
import com.bufferbrew.floatnote.ai.ChatClient
import com.bufferbrew.floatnote.ai.NoteAssistant
import com.bufferbrew.floatnote.data.ExportingNoteRepository
import com.bufferbrew.floatnote.data.MarkdownExport
import com.bufferbrew.floatnote.data.NoteRepository
import com.bufferbrew.floatnote.data.RecordingStore
import com.bufferbrew.floatnote.data.RoomNoteRepository
import com.bufferbrew.floatnote.data.RoomVoiceRepository
import com.bufferbrew.floatnote.data.VoiceRepository
import com.bufferbrew.floatnote.data.db.FloatNoteDatabase
import com.bufferbrew.floatnote.domain.Vocabulary
import com.bufferbrew.floatnote.net.ConnectionCheck
import com.bufferbrew.floatnote.domain.model.Dictation
import com.bufferbrew.floatnote.domain.model.VocabEntry
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.util.strings
import com.bufferbrew.floatnote.voice.AndroidLiveRecognizer
import com.bufferbrew.floatnote.voice.AudioFocusInterruptions
import com.bufferbrew.floatnote.voice.MediaAudioRecorder
import com.bufferbrew.floatnote.voice.TranscriptPipeline
import com.bufferbrew.floatnote.voice.VoiceController
import com.bufferbrew.floatnote.voice.VoiceSettings
import com.bufferbrew.floatnote.voice.WhisperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Manual DI root. The Activity and the overlay service share these instances. */
class FloatNoteApp : Application() {
    private val database by lazy { FloatNoteDatabase.create(this) }

    /** The Markdown folder (Settings → Backup), kept up to date by [repository]. */
    val markdownExport: MarkdownExport by lazy { MarkdownExport(this) }

    private val exportingRepository by lazy {
        ExportingNoteRepository(RoomNoteRepository(database.noteDao()), markdownExport, persistScope)
    }

    val repository: NoteRepository get() = exportingRepository

    /** Picks (or with null, stops) the Markdown folder; a new folder gets every note at once. */
    suspend fun setMarkdownFolder(tree: Uri?) {
        markdownExport.setFolder(tree)
        exportingRepository.exportAll()
    }

    /** Whisper recordings kept after transcription, for History's playback. */
    val recordings: RecordingStore by lazy {
        RecordingStore(File(filesDir, "recordings"), enabled = { voiceSettings.keepRecordings })
    }

    /** Dictation history and the vocabulary. */
    val voiceRepository: VoiceRepository by lazy { RoomVoiceRepository(database.voiceDao(), recordings = recordings) }

    /** Outlives any screen, so saves started as a screen closes still finish. */
    val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** App-lifetime main-thread scope for work that drives UI state (voice sampling). */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val voiceSettings: VoiceSettings by lazy { VoiceSettings(this) }

    /** Settings' "Test connection". */
    val connectionCheck: ConnectionCheck by lazy { ConnectionCheck(strings(), ChatClient(strings())) }

    /** What ✦ Organise knows about each note category. */
    val categoryContext: CategoryContext by lazy { CategoryContext(this) }

    /** AI clean-up and Organise, on the same OpenAI-compatible server and key as Whisper. */
    val assistant: NoteAssistant by lazy {
        NoteAssistant(
            strings(),
            ChatClient(strings()),
            voiceSettings::llmConfig,
            voiceSettings::configProblem,
            categoryContext = categoryContext::get,
            relatedTitles = { note -> note.category?.let { repository.titlesIn(it, note.id, RELATED_TITLES) }.orEmpty() },
            addSuggestions = { voiceSettings.aiSuggestions },
        )
    }

    /**
     * The one editing session for the whole process. App-scoped rather than Activity-scoped so
     * the floating overlay and the full app edit the same in-memory note. Hosts call
     * [NoteViewModel.flush] when they go away, since this is never cleared.
     */
    val session: NoteViewModel by lazy {
        NoteViewModel(repository, persistScope, strings(), organiser = assistant)
    }

    /** Kept current in memory, because recognition needs it synchronously at each start. */
    val vocabulary: StateFlow<List<VocabEntry>> by lazy {
        voiceRepository.observeVocabulary().stateIn(persistScope, SharingStarted.Eagerly, emptyList())
    }

    private val transcripts: TranscriptPipeline by lazy {
        TranscriptPipeline(
            scope = mainScope,
            vocabulary = { vocabulary.value },
            cleanupEnabled = { voiceSettings.aiCleanup && voiceSettings.llmConfig() != null },
            cleanUp = assistant::cleanUp,
            addToNote = session::addTranscript,
            addToHistory = { noteId, text, raw, audio ->
                voiceRepository.addDictation(noteId, text, raw, recordings.keep(audio))
            },
        )
    }

    /** One recorder for the process, so the bubble and the editor show the same recording. */
    val voice: VoiceController by lazy {
        VoiceController(
            strings = strings(),
            recorder = MediaAudioRecorder(this),
            transcriber = WhisperClient(strings()),
            liveRecognizer = AndroidLiveRecognizer(this),
            engine = { voiceSettings.engine },
            config = { voiceSettings.config()?.copy(prompt = Vocabulary.whisperPrompt(vocabulary.value)) },
            configProblem = voiceSettings::configProblem,
            language = { voiceSettings.language },
            hints = { Vocabulary.hints(vocabulary.value) },
            audioDir = voiceClips.apply { mkdirs() },
            scope = mainScope,
            onTranscript = transcripts::submit,
            interruptions = AudioFocusInterruptions(this),
            online = ::isOnline,
        )
    }

    private val voiceClips get() = File(cacheDir, "voice")

    /**
     * Sends a kept recording through Whisper again; the new text is added to its note. False if
     * there's no recording or a recording or upload is already going.
     */
    fun transcribeAgain(dictation: Dictation): Boolean {
        val audio = recordings.file(dictation.audio) ?: return false
        if (voice.isBusy) return false
        val copy = voice.sharedClipFile(dictation.noteId, 0, audio.extension)
        if (runCatching { audio.copyTo(copy, overwrite = true) }.isFailure) return false
        return voice.transcribeFiles(listOf(copy), dictation.noteId)
    }

    /** [transcribeAgain], with a toast when it can't start (History's button). */
    fun transcribeAgainOrSay(dictation: Dictation) {
        if (!transcribeAgain(dictation)) Toast.makeText(this, R.string.history_transcribe_busy, Toast.LENGTH_SHORT).show()
    }

    private fun isOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        /** Other notes' titles sent with ✦ Organise: enough to spot related work, still short. */
        const val RELATED_TITLES = 15
    }

    override fun onCreate() {
        super.onCreate()
        // Start loading the vocabulary now (off the main thread), so it's ready for the first recording.
        vocabulary
    }
}
