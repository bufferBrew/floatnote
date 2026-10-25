package com.bufferbrew.floatnote.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bufferbrew.floatnote.ai.labelRes
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.ui.theme.Dimens

/**
 * One chip per [NoteCategory], the [selected] one filled, led by a chip for "none" labelled
 * [noneLabel] (All in the list, No category in the editor). Scrolls sideways so it fits the
 * floating window.
 */
@Composable
fun CategoryChips(
    selected: NoteCategory?,
    onSelect: (NoteCategory?) -> Unit,
    noneLabel: String,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (dense) Dimens.SpaceXs else Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(noneLabel, { onSelect(null) }, selected = selected == null, dense = dense)
        NoteCategory.entries.forEach { category ->
            PillButton(stringResource(category.labelRes), { onSelect(category) }, selected = selected == category, dense = dense)
        }
    }
}
