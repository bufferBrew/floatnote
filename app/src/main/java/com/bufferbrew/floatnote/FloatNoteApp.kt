package com.bufferbrew.floatnote

import android.app.Application
import com.bufferbrew.floatnote.data.NoteRepository
import com.bufferbrew.floatnote.data.RoomNoteRepository
import com.bufferbrew.floatnote.data.db.FloatNoteDatabase
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.voice.MediaAudioRecorder
import com.bufferbrew.floatnote.voice.VoiceController
import com.bufferbrew.floatnote.voice.VoiceSettings
import com.bufferbrew.floatnote.voice.WhisperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual DI root. The Activity and the overlay service share these instances. */
class FloatNoteApp : Application() {
    val repository: NoteRepository by lazy {
        RoomNoteRepository(FloatNoteDatabase.create(this).noteDao())
    }

    /** Outlives any screen, so saves started as a screen closes still finish. */
    val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** App-lifetime main-thread scope for work that drives UI state (voice sampling). */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The one editing session for the whole process. App-scoped rather than Activity-scoped so
     * the floating overlay and the full app edit the same in-memory note. Hosts call
     * [NoteViewModel.flush] when they go away, since this is never cleared.
     */
    val session: NoteViewModel by lazy { NoteViewModel(repository, persistScope) }

    val voiceSettings: VoiceSettings by lazy { VoiceSettings(this) }

    /** One recorder for the process, so the bubble and the editor show the same recording. */
    val voice: VoiceController by lazy {
        VoiceController(
            recorder = MediaAudioRecorder(this),
            transcriber = WhisperClient(),
            config = voiceSettings::config,
            audioDir = File(cacheDir, "voice").apply { mkdirs() },
            scope = mainScope,
            onTranscript = session::addTranscript,
        )
    }
}
