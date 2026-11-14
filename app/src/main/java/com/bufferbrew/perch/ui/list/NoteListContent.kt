package com.bufferbrew.perch.ui.list

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.bufferbrew.perch.R
import com.bufferbrew.perch.ai.labelRes
import com.bufferbrew.perch.domain.model.NoteCategory
import com.bufferbrew.perch.domain.model.NoteSummary
import com.bufferbrew.perch.ui.components.CategoryChips
import com.bufferbrew.perch.ui.components.CircleButton
import com.bufferbrew.perch.ui.components.PillButton
import com.bufferbrew.perch.ui.theme.Dimens
import com.bufferbrew.perch.ui.theme.Motion
import kotlinx.coroutines.delay

private const val UNDO_MS = 8_000L

/** The notes library, shared by the Activity and the floating panel. */
@Composable
fun NoteListContent(
    notes: List<NoteSummary>?,
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
    onSettings: (() -> Unit)? = null,
    onFloat: (() -> Unit)? = null,
) {
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val gap = if (compact) Dimens.SpaceXs else Dimens.SpaceM
    Column(modifier) {
        LazyVerticalGrid(
            columns = if (compact) GridCells.Fixed(1) else GridCells.Adaptive(Dimens.NoteCardWidth),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = gap, bottom = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    if (compact) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                        ) {
                            PillButton(stringResource(R.string.new_note), onCreate, selected = true, dense = true)
                            onHistory?.let { PillButton(stringResource(R.string.history_title), it, dense = true) }
                            onDictionary?.let { PillButton(stringResource(R.string.vocab_title), it, dense = true) }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceS),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.notes_title),
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            PillButton(stringResource(R.string.new_note), onCreate, selected = true)
                        }
                    }
                    if (onQueryChange != null) SearchField(query, onQueryChange, compact)
                    if (onCategoryFilter != null) {
                        CategoryChips(
                            selected = categoryFilter,
                            onSelect = onCategoryFilter,
                            noneLabel = stringResource(R.string.category_all),
                            dense = compact,
                        )
                    }
                }
            }
            if (notes != null && notes.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    val colors = MaterialTheme.colorScheme
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dimens.CornerL))
                            .background(colors.surfaceContainerLow)
                            .padding(if (compact) Dimens.SpaceM else Dimens.SpaceXl),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    ) {
                        Text(
                            when {
                                query.isNotBlank() -> stringResource(R.string.search_none)
                                categoryFilter != null ->
                                    stringResource(R.string.no_notes_in_category, stringResource(categoryFilter.labelRes))
                                else -> stringResource(R.string.no_notes)
                            },
                            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        )
                        if (!compact && query.isBlank() && categoryFilter == null) {
                            Text(
                                stringResource(R.string.no_notes_help),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(notes.orEmpty(), key = { it.id }, contentType = { "note" }) { summary ->
                val showActions = actionsFor == summary.id
                NoteCard(
                    summary = summary,
                    compact = compact,
                    showCategory = summary.category != categoryFilter,
                    showActions = showActions,
                    onOpen = { onOpen(summary.id) },
                    onActions = { actionsFor = if (showActions) null else summary.id },
                    onPin = onPin?.let { pin ->
                        {
                            pin(summary.id, !summary.pinned)
                            actionsFor = null
                        }
                    },
                    onDelete = {
                        onDelete(summary.id)
                        actionsFor = null
                    },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(Motion.Quick),
                        fadeOutSpec = tween(Motion.Quick),
                    ),
                )
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
            LaunchedEffect(deletedTitle) {
                delay(UNDO_MS)
                onDeletedDismissed()
            }
        }
        if (!compact && (onHistory != null || onDictionary != null || onSettings != null || onFloat != null)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs, Alignment.CenterHorizontally),
            ) {
                onHistory?.let { TextButton(onClick = it) { Text(stringResource(R.string.history_title)) } }
                onDictionary?.let { TextButton(onClick = it) { Text(stringResource(R.string.vocab_title)) } }
                onSettings?.let { TextButton(onClick = it) { Text(stringResource(R.string.settings)) } }
                onFloat?.let { TextButton(onClick = it) { Text(stringResource(R.string.float_button)) } }
            }
        }
    }
}

@Composable
private fun NoteCard(
    summary: NoteSummary,
    compact: Boolean,
    showCategory: Boolean,
    showActions: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    onPin: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shape = RoundedCornerShape(if (compact) Dimens.CornerM else Dimens.CornerL)
    val actionsLabel = stringResource(R.string.note_actions)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val configuration = LocalConfiguration.current
    val updated = remember(summary.updatedAt, configuration) {
        DateUtils.getRelativeTimeSpanString(summary.updatedAt).toString()
    }
    val metadata = listOfNotNull(
        stringResource(R.string.pinned).takeIf { summary.pinned },
        summary.category?.takeIf { showCategory }?.let { stringResource(it.labelRes) },
        updated,
    )
    Column(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(if (summary.pinned) colors.primaryContainer else colors.surfaceContainerLow)
            .border(
                if (focused) Dimens.FocusRing else Dimens.Hairline,
                if (focused) colors.primary else colors.outlineVariant,
                shape,
            ),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f)
                    .heightIn(min = if (compact) Dimens.CompactTouchTarget else Dimens.TouchTarget)
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onLongClickLabel = actionsLabel,
                        onLongClick = onActions,
                        onClick = onOpen,
                    )
                    .padding(if (compact) Dimens.SpaceS else Dimens.SpaceM),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                Text(
                    summary.title.ifBlank { stringResource(R.string.untitled) },
                    style = if (compact) typography.titleMedium else typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.preview.isNotBlank()) {
                    Text(
                        summary.preview,
                        style = typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    metadata.joinToString(" · "),
                    style = typography.labelMedium,
                    color = if (summary.pinned) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
            }
            CircleButton(
                "⋯", actionsLabel, onActions,
                modifier = Modifier.padding(top = Dimens.SpaceXs, end = Dimens.SpaceXs),
                dense = compact,
            )
        }
        AnimatedVisibility(
            visible = showActions,
            enter = fadeIn(tween(Motion.Quick)),
            exit = fadeOut(tween(Motion.Quick)),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(Dimens.SpaceS),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                onPin?.let { PillButton(stringResource(if (summary.pinned) R.string.unpin else R.string.pin), it, dense = compact) }
                PillButton(stringResource(R.string.delete), onDelete, dense = compact)
                PillButton(stringResource(R.string.cancel), onActions, dense = compact)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, compact: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        shape = RoundedCornerShape(Dimens.CornerL),
        textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        leadingIcon = {
            Canvas(Modifier.size(Dimens.Icon)) {
                val stroke = Dimens.FocusRing.toPx()
                drawCircle(color, radius = size.width / 4, center = center * 0.8f, style = Stroke(stroke))
                drawLine(color, center * 1.15f, center * 1.65f, stroke, cap = StrokeCap.Round)
            }
        },
        trailingIcon = if (query.isEmpty()) null else {
            { CircleButton("✕", stringResource(R.string.search_clear), { onChange("") }, dense = compact) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
