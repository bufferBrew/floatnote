package com.bufferbrew.floatnote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bufferbrew.floatnote.overlay.openFloatingNote
import com.bufferbrew.floatnote.ui.editor.NoteEditorContent
import com.bufferbrew.floatnote.ui.list.NoteListContent
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.ui.theme.FloatNoteTheme

class MainActivity : ComponentActivity() {

    private val session get() = (application as FloatNoteApp).session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloatNoteTheme {
                Surface(Modifier.fillMaxSize()) {
                    val contentModifier = Modifier
                        .safeDrawingPadding()
                        .imePadding()
                        .padding(horizontal = Dimens.SpaceM)
                    val note = session.note
                    if (note == null) {
                        val notes by session.notes.collectAsState(initial = emptyList())
                        Column(contentModifier) {
                            FloatButton()
                            NoteListContent(
                                notes = notes,
                                onOpen = session::open,
                                onCreate = session::createNote,
                                onDelete = session::deleteNote,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        BackHandler { session.closeNote() }
                        Column(contentModifier) {
                            Row {
                                TextButton(onClick = session::closeNote) {
                                    Text(stringResource(R.string.back_to_notes))
                                }
                                Spacer(Modifier.weight(1f))
                                FloatButton()
                            }
                            NoteEditorContent(
                                note = note,
                                pendingFocus = session.pendingFocus,
                                actions = session,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Hands the open note to the floating window and steps out of the way. */
    @Composable
    private fun FloatButton() {
        TextButton(onClick = {
            if (openFloatingNote()) moveTaskToBack(true)
        }) { Text(stringResource(R.string.float_button)) }
    }

    // The process can be killed any time after this, so don't wait for the save debounce.
    override fun onStop() {
        super.onStop()
        session.flush()
    }
}
