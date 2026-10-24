package com.bufferbrew.floatnote.ui.editor

import android.content.Context
import android.content.Intent
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.NoteText
import com.bufferbrew.floatnote.domain.model.Note

/** Opens the share sheet for [note] (which also offers Copy). Works from the overlay too. */
fun Context.shareNote(note: Note) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, NoteText.of(note))
        .apply { if (note.title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, note.title) }
    startActivity(
        Intent.createChooser(send, getString(R.string.share_note)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
