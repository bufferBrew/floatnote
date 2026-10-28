package com.bufferbrew.floatnote.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.ai.CategoryContext
import com.bufferbrew.floatnote.ai.labelRes
import com.bufferbrew.floatnote.data.RecordingStore
import com.bufferbrew.floatnote.domain.model.NoteCategory
import com.bufferbrew.floatnote.net.CheckLine
import com.bufferbrew.floatnote.net.ConnectionCheck
import com.bufferbrew.floatnote.ui.components.AppSwitch
import com.bufferbrew.floatnote.ui.components.PillButton
import com.bufferbrew.floatnote.ui.theme.Dimens
import com.bufferbrew.floatnote.voice.VoiceEngine
import com.bufferbrew.floatnote.voice.VoiceSettings
import kotlinx.coroutines.launch

/** The Settings pages. Dictionary and History are pages of their own beside the notes. */
enum class SettingsPage { Voice, Api, Ai, Floating, Backup }

/**
 * Settings: a short menu of categories, each opening its own page. Text fields save as you
 * type, except the API key, which is only written when you tap Save and is never shown back
 * (the field starts empty).
 */
@Composable
fun SettingsContent(
    settings: VoiceSettings,
    /** What ✦ Organise knows per note category; edited on the AI page. */
    categories: CategoryContext,
    /** Runs "Test connection" on the API and AI pages. */
    connectionCheck: ConnectionCheck,
    /** Kept Whisper recordings: the Voice page's switch and "Delete recordings". */
    recordings: RecordingStore,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    openNewNote: Boolean,
    onOpenNewNoteChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    /** Result of the last export or import, shown on the Backup page. */
    backupMessage: String?,
    /** The Markdown export folder's name, or null when it's off. */
    markdownFolder: String?,
    onChooseMarkdownFolder: () -> Unit,
    onStopMarkdown: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val colors = MaterialTheme.colorScheme
    // Registered after the host's handler, so Back leaves the page before it leaves Settings.
    BackHandler(enabled = page != null) { page = null }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        val current = page
        TextButton(onClick = { if (current == null) onBack() else page = null }) {
            Text(
                stringResource(if (current == null) R.string.back_to_notes else R.string.back_to_settings),
                color = colors.onSurfaceVariant,
            )
        }
        when (current) {
            null -> SettingsMenu(onOpen = { page = it })
            SettingsPage.Voice -> VoicePage(settings, recordings, micGranted, onRequestMic)
            SettingsPage.Api -> ApiPage(settings, connectionCheck)
            SettingsPage.Ai -> AiPage(settings, categories, connectionCheck)
            SettingsPage.Floating -> FloatingPage(openNewNote, onOpenNewNoteChange)
            SettingsPage.Backup -> BackupPage(onExport, onImport, backupMessage, markdownFolder, onChooseMarkdownFolder, onStopMarkdown)
        }
    }
}

@Composable
private fun SettingsMenu(onOpen: (SettingsPage) -> Unit) {
    Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
    Column {
        SettingsPage.entries.forEach { target ->
            val (title, help) = when (target) {
                SettingsPage.Voice -> R.string.settings_page_voice to R.string.settings_page_voice_help
                SettingsPage.Api -> R.string.settings_page_api to R.string.settings_page_api_help
                SettingsPage.Ai -> R.string.settings_ai to R.string.settings_page_ai_help
                SettingsPage.Floating -> R.string.settings_page_floating to R.string.settings_page_floating_help
                SettingsPage.Backup -> R.string.settings_page_backup to R.string.settings_page_backup_help
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(target) }
                    .padding(vertical = Dimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun VoicePage(settings: VoiceSettings, recordings: RecordingStore, micGranted: Boolean, onRequestMic: () -> Unit) {
    var autoRecord by remember { mutableStateOf(settings.autoRecord) }
    var keepRecordings by remember { mutableStateOf(settings.keepRecordings) }
    var recordingsBytes by remember { mutableStateOf(recordings.size()) }
    var engine by remember { mutableStateOf(settings.engine) }
    var model by remember { mutableStateOf(settings.model) }
    var language by remember { mutableStateOf(settings.language) }
    val colors = MaterialTheme.colorScheme

    Text(stringResource(R.string.settings_page_voice), style = MaterialTheme.typography.headlineSmall)
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_mic), modifier = Modifier.weight(1f))
        if (micGranted) {
            Text(stringResource(R.string.settings_mic_granted), color = colors.onSurfaceVariant)
        } else {
            PillButton(stringResource(R.string.settings_mic_allow), onRequestMic, selected = true)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_auto_record), modifier = Modifier.weight(1f))
        AppSwitch(
            checked = autoRecord,
            onCheckedChange = {
                autoRecord = it
                settings.autoRecord = it
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            Text(stringResource(R.string.settings_keep_recordings))
            Text(
                stringResource(R.string.settings_keep_recordings_help),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        AppSwitch(
            checked = keepRecordings,
            onCheckedChange = {
                keepRecordings = it
                settings.keepRecordings = it
            },
        )
    }
    if (recordingsBytes > 0) {
        PillButton(
            stringResource(R.string.settings_delete_recordings, recordingsBytes / (1024 * 1024)),
            {
                recordings.clear()
                recordingsBytes = 0
            },
        )
    }
}

@Composable
private fun ApiPage(settings: VoiceSettings, check: ConnectionCheck) {
    var hasKey by remember { mutableStateOf(settings.hasApiKey) }
    var keyDraft by remember { mutableStateOf("") }
    var keyBad by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var hostConfirmed by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme

    Text(stringResource(R.string.settings_page_api), style = MaterialTheme.typography.headlineSmall)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it; keyBad = false },
            label = { Text(stringResource(R.string.settings_api_key)) },
            isError = keyBad,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                when {
                    keyBad -> R.string.settings_api_key_bad
                    hasKey -> R.string.settings_api_key_saved
                    else -> R.string.settings_api_key_missing
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (keyBad) colors.error else colors.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            PillButton(
                text = stringResource(R.string.settings_save_key),
                onClick = {
                    if (keyDraft.isNotBlank()) {
                        if (settings.setApiKey(keyDraft)) {
                            keyDraft = ""
                            hasKey = true
                        } else {
                            keyBad = true
                        }
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
    ConnectionTest(settings, check)
}

/**
 * "Test connection": checks the saved key, the speech model and the AI model against the server,
 * and lists the result, one line each.
 */
@Composable
private fun ConnectionTest(settings: VoiceSettings, check: ConnectionCheck) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CheckLine>>(emptyList()) }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        PillButton(
            text = stringResource(if (running) R.string.check_running else R.string.check_run),
            onClick = {
                if (running) return@PillButton
                running = true
                scope.launch {
                    results = check.run(settings.config(), settings.configProblem(), settings.aiModel)
                    running = false
                }
            },
            selected = true,
        )
        Text(stringResource(R.string.check_help), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (results.isNotEmpty()) {
            Column(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                results.forEach { line ->
                    val (mark, status) = when (line.ok) {
                        true -> "✓" to stringResource(R.string.check_passed)
                        false -> "✕" to stringResource(R.string.check_failed)
                        null -> "–" to stringResource(R.string.check_unknown)
                    }
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "${line.label}: $status. ${line.detail}"
                        },
                    ) {
                        Text(
                            mark,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (line.ok == false) colors.error else colors.onSurface,
                            modifier = Modifier.padding(end = Dimens.SpaceS),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(line.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                line.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (line.ok == false) colors.error else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPage(settings: VoiceSettings, categories: CategoryContext, check: ConnectionCheck) {
    var aiCleanup by remember { mutableStateOf(settings.aiCleanup) }
    var aiSuggestions by remember { mutableStateOf(settings.aiSuggestions) }
    var aiModel by remember { mutableStateOf(settings.aiModel) }
    val hasKey = remember { settings.hasApiKey }

    Text(stringResource(R.string.settings_ai), style = MaterialTheme.typography.headlineSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_ai_cleanup))
            Text(
                stringResource(if (hasKey) R.string.settings_ai_cleanup_help else R.string.settings_ai_needs_key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSwitch(
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_ai_suggest))
            Text(
                stringResource(R.string.settings_ai_suggest_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSwitch(
            checked = aiSuggestions,
            onCheckedChange = {
                aiSuggestions = it
                settings.aiSuggestions = it
            },
        )
    }
    Text(stringResource(R.string.settings_category_context), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.settings_category_context_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ConnectionTest(settings, check)
    NoteCategory.entries.forEach { category -> CategoryContextField(category, categories) }
}

/** One category's context: a multi-line field that saves as you type, with Reset back to the default. */
@Composable
private fun CategoryContextField(category: NoteCategory, categories: CategoryContext) {
    var text by remember { mutableStateOf(categories.get(category)) }
    val default = remember { categories.default(category) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                categories.set(category, it)
            },
            label = { Text(stringResource(category.labelRes)) },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        if (text.trim() != default.trim()) {
            TextButton(onClick = {
                text = default
                categories.set(category, default)
            }) { Text(stringResource(R.string.settings_category_reset)) }
        }
    }
}

@Composable
private fun FloatingPage(openNewNote: Boolean, onOpenNewNoteChange: (Boolean) -> Unit) {
    Text(stringResource(R.string.settings_page_floating), style = MaterialTheme.typography.headlineSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_float_new_note))
            Text(
                stringResource(R.string.settings_float_new_note_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSwitch(checked = openNewNote, onCheckedChange = onOpenNewNoteChange)
    }
}

@Composable
private fun BackupPage(
    onExport: () -> Unit,
    onImport: () -> Unit,
    message: String?,
    markdownFolder: String?,
    onChooseMarkdownFolder: () -> Unit,
    onStopMarkdown: () -> Unit,
) {
    Text(stringResource(R.string.settings_page_backup), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.backup_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        PillButton(stringResource(R.string.backup_export), onExport, selected = true)
        PillButton(stringResource(R.string.backup_import), onImport)
    }
    if (message != null) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Text(stringResource(R.string.markdown_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.markdown_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (markdownFolder != null) {
        Text(stringResource(R.string.markdown_saving_to, markdownFolder), style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        PillButton(
            stringResource(if (markdownFolder == null) R.string.markdown_choose else R.string.markdown_change),
            onChooseMarkdownFolder,
            selected = markdownFolder == null,
        )
        if (markdownFolder != null) PillButton(stringResource(R.string.markdown_stop), onStopMarkdown)
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
