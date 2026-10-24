package com.bufferbrew.floatnote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.domain.model.VocabEntry
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.voice.VoiceEngine
import com.bufferbrew.floatnote.voice.VoiceSettings

/**
 * Voice settings. Text fields save as you type, except the API key, which is only written
 * when you tap Save and is never shown back (the field starts empty).
 */
@Composable
fun SettingsContent(
    settings: VoiceSettings,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    vocabulary: List<VocabEntry>,
    onAddVocab: (term: String, replacement: String) -> Unit,
    onDeleteVocab: (Long) -> Unit,
    openNewNote: Boolean,
    onOpenNewNoteChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var autoRecord by remember { mutableStateOf(settings.autoRecord) }
    var engine by remember { mutableStateOf(settings.engine) }
    var hasKey by remember { mutableStateOf(settings.hasApiKey) }
    var keyDraft by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var language by remember { mutableStateOf(settings.language) }
    var hostConfirmed by remember { mutableIntStateOf(0) }
    var aiCleanup by remember { mutableStateOf(settings.aiCleanup) }
    var aiModel by remember { mutableStateOf(settings.aiModel) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back_to_notes), color = colors.onSurfaceVariant)
        }
        Text(stringResource(R.string.settings_voice), style = MaterialTheme.typography.headlineSmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_auto_record), modifier = Modifier.weight(1f))
            Switch(
                checked = autoRecord,
                onCheckedChange = {
                    autoRecord = it
                    settings.autoRecord = it
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_float_new_note))
                Text(
                    stringResource(R.string.settings_float_new_note_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(checked = openNewNote, onCheckedChange = onOpenNewNoteChange)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_mic), modifier = Modifier.weight(1f))
            if (micGranted) {
                Text(stringResource(R.string.settings_mic_granted), color = colors.onSurfaceVariant)
            } else {
                PillButton(stringResource(R.string.settings_mic_allow), onRequestMic, selected = true)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.settings_engine))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                VoiceEngine.entries.forEach { option ->
                    PillButton(
                        text = stringResource(
                            if (option == VoiceEngine.Device) R.string.engine_device else R.string.engine_whisper
                        ),
                        onClick = {
                            engine = option
                            settings.engine = option
                        },
                        selected = engine == option,
                    )
                }
            }
            Text(
                stringResource(
                    if (engine == VoiceEngine.Device) R.string.engine_device_help else R.string.engine_whisper_help
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text(stringResource(R.string.settings_api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(if (hasKey) R.string.settings_api_key_saved else R.string.settings_api_key_missing),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                PillButton(
                    text = stringResource(R.string.settings_save_key),
                    onClick = {
                        if (keyDraft.isNotBlank()) {
                            settings.setApiKey(keyDraft)
                            keyDraft = ""
                            hasKey = true
                        }
                    },
                    selected = keyDraft.isNotBlank(),
                )
                if (hasKey) {
                    PillButton(
                        text = stringResource(R.string.settings_remove_key),
                        onClick = {
                            settings.setApiKey("")
                            hasKey = false
                        },
                    )
                }
            }
        }

        SettingField(
            value = baseUrl,
            onChange = { baseUrl = it; settings.baseUrl = it },
            label = stringResource(R.string.settings_base_url),
            help = stringResource(R.string.settings_base_url_help),
            keyboardType = KeyboardType.Uri,
        )
        // Re-read on every change of address or key; hostConfirmed bumps it after Allow.
        val urlValid = remember(baseUrl) { settings.baseUrlValid }
        val needsConfirm = remember(baseUrl, hasKey, hostConfirmed) { settings.hostNeedsConfirm }
        if (!urlValid) {
            Text(
                stringResource(R.string.settings_base_url_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        } else if (needsConfirm) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_host_confirm, settings.keyHost.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = stringResource(R.string.settings_host_allow),
                    onClick = {
                        settings.confirmHost()
                        hostConfirmed++
                    },
                    selected = true,
                )
            }
        }
        if (engine == VoiceEngine.Whisper) SettingField(
            value = model,
            onChange = { model = it; settings.model = it },
            label = stringResource(R.string.settings_model),
            help = stringResource(R.string.settings_model_help),
        )
        SettingField(
            value = language,
            onChange = { language = it; settings.language = it },
            label = stringResource(R.string.settings_language),
            help = stringResource(R.string.settings_language_help),
        )

        Text(stringResource(R.string.settings_ai), style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_ai_cleanup))
                Text(
                    stringResource(if (hasKey) R.string.settings_ai_cleanup_help else R.string.settings_ai_needs_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(
                checked = aiCleanup,
                onCheckedChange = {
                    aiCleanup = it
                    settings.aiCleanup = it
                },
            )
        }
        SettingField(
            value = aiModel,
            onChange = { aiModel = it; settings.aiModel = it },
            label = stringResource(R.string.settings_ai_model),
            help = stringResource(R.string.settings_ai_model_help),
        )

        VocabularySection(vocabulary, onAddVocab, onDeleteVocab)
    }
}

/** Wispr-style dictionary: words to recognise, optionally rewritten to something else. */
@Composable
private fun VocabularySection(
    entries: List<VocabEntry>,
    onAdd: (String, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var term by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.vocab_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.vocab_help),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = term,
            onValueChange = { term = it },
            label = { Text(stringResource(R.string.vocab_word)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            label = { Text(stringResource(R.string.vocab_replacement)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PillButton(
            text = stringResource(R.string.vocab_add),
            onClick = {
                if (term.isNotBlank()) {
                    onAdd(term, replacement)
                    term = ""
                    replacement = ""
                }
            },
            selected = term.isNotBlank(),
        )
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (entry.replacement.isBlank()) entry.term else "${entry.term} → ${entry.replacement}",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDelete(entry.id) }) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun SettingField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    help: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.SpaceXs),
        )
    }
}
