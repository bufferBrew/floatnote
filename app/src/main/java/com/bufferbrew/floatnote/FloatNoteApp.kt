package com.bufferbrew.floatnote

import android.app.Application
import com.bufferbrew.floatnote.data.NoteRepository
import com.bufferbrew.floatnote.data.RoomNoteRepository
import com.bufferbrew.floatnote.data.db.FloatNoteDatabase
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI root. The Activity and (later) the overlay service share these instances. */
class FloatNoteApp : Application() {
    val repository: NoteRepository by lazy {
        RoomNoteRepository(FloatNoteDatabase.create(this).noteDao())
    }

    /** Outlives any screen, so saves started as a screen closes still finish. */
    val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The one editing session for the whole process. App-scoped rather than Activity-scoped so
     * the floating overlay and the full app edit the same in-memory note. Hosts call
     * [NoteViewModel.flush] when they go away, since this is never cleared.
     */
    val session: NoteViewModel by lazy { NoteViewModel(repository, persistScope) }
}
