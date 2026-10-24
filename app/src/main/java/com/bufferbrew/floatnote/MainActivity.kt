package com.bufferbrew.floatnote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import com.bufferbrew.floatnote.domain.NoteEditor
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.ui.theme.FloatNoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloatNoteTheme {
                Surface(Modifier.fillMaxSize()) { SampleNote() }
            }
        }
    }
}

/** Placeholder until the real editor exists: shows the block model working. */
@Composable
private fun SampleNote() {
    val editor = remember { NoteEditor() }
    var note by remember {
        mutableStateOf(
            Note(
                id = "sample",
                title = "Sample note",
                blocks = listOf(
                    Block.Text("t1", "Things to do today"),
                    Block.Task("k1", "Reply to email"),
                    Block.Task("k2", "Book dentist"),
                    Block.Task("k3", "Pay rent"),
                    Block.Checklist("c1", "Packed charger"),
                ),
            )
        )
    }
    Column(
        modifier = Modifier.safeDrawingPadding().padding(Dimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        Text(note.title, style = MaterialTheme.typography.headlineSmall)
        note.blocks.forEach { block ->
            when (block) {
                is Block.Text -> Text(block.text)
                is Block.Checklist -> CheckRow(block.text, block.checked, strike = block.checked) {
                    note = editor.toggle(note, block.id)
                }
                is Block.Task -> CheckRow(block.text, block.done, strike = false) {
                    note = editor.toggle(note, block.id)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, strike: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(text, textDecoration = if (strike) TextDecoration.LineThrough else null)
    }
}
