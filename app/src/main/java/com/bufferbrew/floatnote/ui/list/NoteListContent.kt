package com.bufferbrew.floatnote.ui.list

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.NoteSummary
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.theme.Dimens
import kotlinx.coroutines.delay

private const val UNDO_MS = 8_000L

/**
 * The note list. Stateless and host-agnostic like the editor, so the floating overlay can
 * show it too. Long-press a note to delete it; an Undo bar brings it back for a few seconds.
 * (No confirm dialog: dialogs can't open from the floating panel, which has no Activity.)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteListContent(
    notes: List<NoteSummary>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    onHistory: (() -> Unit)? = null,
    compact: Boolean = false,
    deletedTitle: String? = null,
    onUndoDelete: () -> Unit = {},
    onDeletedDismissed: () -> Unit = {},
) {
    val deleteLabel = stringResource(R.string.delete)
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.notes_title),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (onHistory != null) {
                PillButton(stringResource(R.string.history_title), onHistory, dense = compact)
                Spacer(Modifier.width(Dimens.SpaceS))
            }
            PillButton(stringResource(R.string.new_note), onCreate, selected = true, dense = compact)
        }
        if (notes.isEmpty()) {
            // weight, not fillMaxSize, so the Undo bar below still fits after deleting the last note.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                                onLongClick = { onDelete(summary.id) },
                                onLongClickLabel = deleteLabel,
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (deletedTitle != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceXs)
                    .clip(RoundedCornerShape(Dimens.CornerM))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = Dimens.SpaceM)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.note_deleted, deletedTitle.ifBlank { stringResource(R.string.untitled) }),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onUndoDelete) { Text(stringResource(R.string.undo)) }
            }
        }
    }

    // The Undo bar goes away by itself after a few seconds.
    if (deletedTitle != null) {
        LaunchedEffect(deletedTitle) {
            delay(UNDO_MS)
            onDeletedDismissed()
        }
    }
}
