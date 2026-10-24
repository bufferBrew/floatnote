package com.bufferbrew.floatnote.ui.list

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.NoteSummary
import com.bufferbrew.floatnote.ui.theme.Dimens

/**
 * The note list. Stateless and host-agnostic like the editor, so the floating overlay can
 * show it too. Long-press a note to delete it (with confirmation: notes are user data).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteListContent(
    notes: List<NoteSummary>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<NoteSummary?>(null) }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.notes_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onCreate) { Text(stringResource(R.string.new_note)) }
        }
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_notes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(notes, key = { it.id }) { summary ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpen(summary.id) },
                                onLongClick = { pendingDelete = summary },
                            )
                            .padding(vertical = Dimens.SpaceS)
                            .animateItem(),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    ) {
                        Text(
                            summary.title.ifBlank { stringResource(R.string.untitled) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary.preview.isNotBlank()) {
                            Text(
                                summary.preview,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            DateUtils.getRelativeTimeSpanString(summary.updatedAt).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_note_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_note_body,
                        target.title.ifBlank { stringResource(R.string.untitled) },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
