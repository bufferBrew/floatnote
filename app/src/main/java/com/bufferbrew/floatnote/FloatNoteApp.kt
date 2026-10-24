package com.bufferbrew.floatnote

import android.app.Application
import com.bufferbrew.floatnote.ai.ChatClient
import com.bufferbrew.floatnote.ai.NoteAssistant
import com.bufferbrew.floatnote.data.NoteRepository
import com.bufferbrew.floatnote.data.RoomNoteRepository
import com.bufferbrew.floatnote.data.RoomVoiceRepository
import com.bufferbrew.floatnote.data.VoiceRepository
import com.bufferbrew.floatnote.data.db.FloatNoteDatabase
import com.bufferbrew.floatnote.domain.Vocabulary
import com.bufferbrew.floatnote.domain.model.VocabEntry
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.voice.AndroidLiveRecognizer
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

    val repository: NoteRepository by lazy { RoomNoteRepository(database.noteDao()) }

    /** Dictation history and the vocabulary. */
    val voiceRepository: VoiceRepository by lazy { RoomVoiceRepository(database.voiceDao()) }

    /** Outlives any screen, so saves started as a screen closes still finish. */
    val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** App-lifetime main-thread scope for work that drives UI state (voice sampling). */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val voiceSettings: VoiceSettings by lazy { VoiceSettings(this) }

    /** AI clean-up and Organise, on the same OpenAI-compatible server and key as Whisper. */
    val assistant: NoteAssistant by lazy { NoteAssistant(ChatClient(), voiceSettings::llmConfig, voiceSettings::configProblem) }

    /**
     * The one editing session for the whole process. App-scoped rather than Activity-scoped so
     * the floating overlay and the full app edit the same in-memory note. Hosts call
     * [NoteViewModel.flush] when they go away, since this is never cleared.
     */
    val session: NoteViewModel by lazy {
        NoteViewModel(repository, persistScope, organiser = assistant)
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
            addToHistory = voiceRepository::addDictation,
        )
    }

    /** One recorder for the process, so the bubble and the editor show the same recording. */
    val voice: VoiceController by lazy {
        VoiceController(
            recorder = MediaAudioRecorder(this),
            transcriber = WhisperClient(),
            liveRecognizer = AndroidLiveRecognizer(this),
            engine = { voiceSettings.engine },
            config = { voiceSettings.config()?.copy(prompt = Vocabulary.whisperPrompt(vocabulary.value)) },
            configProblem = voiceSettings::configProblem,
            language = { voiceSettings.language },
            hints = { Vocabulary.hints(vocabulary.value) },
            audioDir = File(cacheDir, "voice").apply { mkdirs() },
            scope = mainScope,
            onTranscript = transcripts::submit,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Start loading the vocabulary now (off the main thread), so it's ready for the first recording.
        vocabulary
    }
}
