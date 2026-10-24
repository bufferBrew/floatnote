package com.bufferbrew.floatnote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bufferbrew.floatnote.ui.editor.NoteEditorContent
import com.bufferbrew.floatnote.ui.editor.NoteViewModel
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.ui.theme.FloatNoteTheme

class MainActivity : ComponentActivity() {

    private val viewModel: NoteViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as FloatNoteApp
                NoteViewModel(app.repository, app.persistScope)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloatNoteTheme {
                Surface(Modifier.fillMaxSize()) {
                    val note = viewModel.note ?: return@Surface
                    NoteEditorContent(
                        note = note,
                        pendingFocus = viewModel.pendingFocus,
                        actions = viewModel,
                        modifier = Modifier
                            .safeDrawingPadding()
                            .imePadding()
                            .padding(horizontal = Dimens.SpaceM),
                    )
                }
            }
        }
    }

    // The process can be killed any time after this, so don't wait for the save debounce.
    override fun onStop() {
        super.onStop()
        viewModel.flush()
    }
}
