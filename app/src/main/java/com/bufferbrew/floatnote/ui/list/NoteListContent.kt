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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.ai.labelRes
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.domain.model.NoteSummary
import com.bufferbrew.floatnote.ui.components.CategoryChips
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.theme.Dimens
import kotlinx.coroutines.delay

private const val UNDO_MS = 8_000L

/**
 * The note list. Stateless and host-agnostic like the editor, so the floating overlay can
 * show it too. Long-press a note for Pin and Delete; an Undo bar brings a deleted note back for
 * a few seconds. The search box filters by title and every line; the category chips by category.
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
    onDictionary: (() -> Unit)? = null,
    compact: Boolean = false,
    deletedTitle: String? = null,
    onUndoDelete: () -> Unit = {},
    onDeletedDismissed: () -> Unit = {},
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
    onPin: ((id: String, pinned: Boolean) -> Unit)? = null,
    categoryFilter: NoteCategory? = null,
    onCategoryFilter: ((NoteCategory?) -> Unit)? = null,
) {
    var actionsFor by remember { mutableStateOf<String?>(null) }
    val actionsLabel = stringResource(R.string.note_actions)
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
            PillButton(stringResource(R.string.new_note), onCreate, selected = true, dense = compact)
        }
        // The other pages, Wispr-style: History and Dictionary sit beside the notes, not in Settings.
        if (onHistory != null || onDictionary != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceS),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                onHistory?.let { PillButton(stringResource(R.string.history_title), it, dense = compact) }
                onDictionary?.let { PillButton(stringResource(R.string.vocab_title), it, dense = compact) }
            }
        }
        if (onQueryChange != null) SearchField(query, onQueryChange, compact)
        if (onCategoryFilter != null) {
            CategoryChips(
                selected = categoryFilter,
                onSelect = onCategoryFilter,
                noneLabel = stringResource(R.string.category_all),
                modifier = Modifier.padding(bottom = Dimens.SpaceS),
                dense = compact,
            )
        }
        if (notes.isEmpty()) {
            // weight, not fillMaxSize, so the Undo bar below still fits after deleting the last note.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        query.isNotBlank() -> stringResource(R.string.search_none)
                        categoryFilter != null ->
                            stringResource(R.string.no_notes_in_category, stringResource(categoryFilter.labelRes))
                        else -> stringResource(R.string.no_notes)
                    },
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
                                onLongClick = { actionsFor = if (actionsFor == summary.id) null else summary.id },
                                onLongClickLabel = actionsLabel,
                            )
                            .padding(vertical = Dimens.SpaceS)
                            .animateItem(),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                summary.title.ifBlank { stringResource(R.string.untitled) },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // "Work · Pinned"; the category is left out when the list already shows only it.
                            val tags = listOfNotNull(
                                summary.category?.takeIf { it != categoryFilter }?.let { stringResource(it.labelRes) },
                                stringResource(R.string.pinned).takeIf { summary.pinned },
                            )
                            if (tags.isNotEmpty()) {
                                Text(
                                    "  " + tags.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
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
                    if (actionsFor == summary.id) {
                        Row(
                            modifier = Modifier.padding(bottom = Dimens.SpaceS),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                        ) {
                            if (onPin != null) {
                                PillButton(
                                    stringResource(if (summary.pinned) R.string.unpin else R.string.pin),
                                    {
                                        onPin(summary.id, !summary.pinned)
                                        actionsFor = null
                                    },
                                    dense = true,
                                )
                            }
                            PillButton(
                                stringResource(R.string.delete),
                                {
                                    onDelete(summary.id)
                                    actionsFor = null
                                },
                                dense = true,
                            )
                            PillButton(stringResource(R.string.cancel), { actionsFor = null }, dense = true)
                        }
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

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    val style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val hint = stringResource(R.string.search_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.SpaceS)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(colors.surfaceVariant)
            .padding(horizontal = Dimens.SpaceM, vertical = if (compact) Dimens.SpaceXs + Dimens.SpaceXs else Dimens.SpaceS + Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.weight(1f).semantics { contentDescription = hint },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(hint, style = style, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            TextButton(onClick = { onChange("") }) { Text(stringResource(R.string.search_clear)) }
        }
    }
}
