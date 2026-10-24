package com.bufferbrew.floatnote.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.Dictation
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.theme.Dimens

/**
 * Every dictation, newest first, like Wispr Flow's history: copy it, jump to the note it went
 * into, or delete it. Tap an entry to see what was heard before clean-up. Stateless and used by
 * both the full app and the floating panel.
 */
@Composable
fun HistoryContent(
    entries: List<Dictation>,
    onOpenNote: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** Swap the cleaned-up text in its note back to what was heard. */
    onUseOriginal: ((Dictation) -> Unit)? = null,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) Dimens.SpaceXs else Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.history_title),
                style = if (compact) typography.titleMedium else typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty() && !confirmClear) {
                PillButton(stringResource(R.string.history_clear), { confirmClear = true }, dense = compact)
            }
        }
        // Inline rather than a dialog: this also runs in the floating panel, which has no Activity.
        if (confirmClear) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.history_clear_body),
                    style = typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text(stringResource(R.string.history_clear)) }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.history_empty), color = colors.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.id }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (expanded == entry.id) null else entry.id }
                        .padding(vertical = Dimens.SpaceS)
                        .animateItem(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                ) {
                    Text(
                        DateUtils.getRelativeTimeSpanString(entry.createdAt).toString(),
                        style = typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    val open = expanded == entry.id
                    Text(
                        entry.text,
                        style = if (compact) typography.bodyMedium else typography.bodyLarge,
                        maxLines = if (open) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (open) {
                        entry.raw?.let { raw ->
                            Text(
                                stringResource(R.string.history_heard, raw),
                                style = typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                            PillButton(stringResource(R.string.history_copy), { context.copy(entry.text) }, dense = true)
                            PillButton(stringResource(R.string.history_open_note), { onOpenNote(entry.noteId) }, dense = true)
                            PillButton(stringResource(R.string.delete), { onDelete(entry.id) }, dense = true)
                        }
                        if (entry.raw != null && onUseOriginal != null) {
                            PillButton(stringResource(R.string.history_use_original), { onUseOriginal(entry) }, dense = true)
                        }
                    }
                }
                HorizontalDivider(color = colors.outline)
            }
        }
    }
}

/** Android 13+ shows its own "Copied" confirmation. */
private fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.history_title), text))
}
