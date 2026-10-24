package com.bufferbrew.floatnote.ui.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.VocabEntry
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.theme.Dimens

/**
 * The Wispr-style dictionary: words to recognise, optionally rewritten to something else.
 * Its own page (not part of Settings), in both the full app and the floating panel.
 */
@Composable
fun DictionaryContent(
    entries: List<VocabEntry>,
    onAdd: (term: String, replacement: String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var term by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val add = {
        if (term.isNotBlank()) {
            onAdd(term, replacement)
            term = ""
            replacement = ""
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(
            stringResource(R.string.vocab_title),
            style = if (compact) typography.titleMedium else typography.titleLarge,
            modifier = Modifier.padding(top = if (compact) Dimens.SpaceXs else Dimens.SpaceS),
        )
        if (!compact) {
            Text(stringResource(R.string.vocab_help), style = typography.bodySmall, color = colors.onSurfaceVariant)
        }
        OutlinedTextField(
            value = term,
            onValueChange = { term = it },
            label = { Text(stringResource(R.string.vocab_word)) },
            singleLine = true,
            textStyle = if (compact) typography.bodyMedium else typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            label = { Text(stringResource(R.string.vocab_replacement)) },
            singleLine = true,
            textStyle = if (compact) typography.bodyMedium else typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        PillButton(stringResource(R.string.vocab_add), add, selected = term.isNotBlank(), dense = compact)
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.vocab_empty), color = colors.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
                        Text(
                            if (entry.replacement.isBlank()) entry.term else "${entry.term} → ${entry.replacement}",
                            style = if (compact) typography.bodyMedium else typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onDelete(entry.id) }) { Text(stringResource(R.string.delete)) }
                    }
                    HorizontalDivider(color = colors.outline)
                }
            }
        }
    }
}
