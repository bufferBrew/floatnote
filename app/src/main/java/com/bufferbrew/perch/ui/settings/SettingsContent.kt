package com.bufferbrew.perch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
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
import com.bufferbrew.perch.R
import com.bufferbrew.perch.ai.CategoryContext
import com.bufferbrew.perch.ai.labelRes
import com.bufferbrew.perch.data.RecordingStore
import com.bufferbrew.perch.domain.model.NoteCategory
import com.bufferbrew.perch.net.CheckLine
import com.bufferbrew.perch.net.ConnectionCheck
import com.bufferbrew.perch.ui.components.AppSwitch
import com.bufferbrew.perch.ui.components.PillButton
import com.bufferbrew.perch.ui.components.ScreenSwitcher
import com.bufferbrew.perch.ui.theme.AppPalette
import com.bufferbrew.perch.ui.theme.Dimens
import com.bufferbrew.perch.voice.VoiceEngine
import com.bufferbrew.perch.voice.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Settings pages. Dictionary and History are pages of their own beside the notes. */
enum class SettingsPage { Voice, Api, Ai, Floating, Appearance, Backup }

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
    /** The colour theme, and the setter that saves and applies a new one at once. */
    palette: AppPalette,
    onPaletteChange: (AppPalette) -> Unit,
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
    // Its back handler is registered after the host's, so Back leaves the page before Settings.
    ScreenSwitcher(
        screen = page ?: SettingsMenuKey,
        depth = { if (it == SettingsMenuKey) 0 else 1 },
        backTo = SettingsMenuKey.takeIf { page != null },
        onBack = { page = null },
        modifier = modifier.fillMaxSize(),
    ) { shown ->
        val current = shown as? SettingsPage
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
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
                SettingsPage.Appearance -> AppearancePage(palette, onPaletteChange)
                SettingsPage.Backup -> BackupPage(onExport, onImport, backupMessage, markdownFolder, onChooseMarkdownFolder, onStopMarkdown)
            }
        }
    }
}

/** The Settings menu's place in [ScreenSwitcher]; the pages are [SettingsPage] values. */
private object SettingsMenuKey

@Composable
private fun SettingsMenu(onOpen: (SettingsPage) -> Unit) {
    Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        SettingsPage.entries.forEach { target ->
            val (title, help) = when (target) {
                SettingsPage.Voice -> R.string.settings_page_voice to R.string.settings_page_voice_help
                SettingsPage.Api -> R.string.settings_page_api to R.string.settings_page_api_help
                SettingsPage.Ai -> R.string.settings_ai to R.string.settings_page_ai_help
                SettingsPage.Floating -> R.string.settings_page_floating to R.string.settings_page_floating_help
                SettingsPage.Appearance -> R.string.settings_page_appearance to R.string.settings_page_appearance_help
                SettingsPage.Backup -> R.string.settings_page_backup to R.string.settings_page_backup_help
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.CornerL))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onOpen(target) }
                    .padding(Dimens.SpaceM),
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
        }
    }
}

@Composable
private fun VoicePage(settings: VoiceSettings, recordings: RecordingStore, micGranted: Boolean, onRequestMic: () -> Unit) {
    var autoRecord by remember { mutableStateOf(settings.autoRecord) }
    var keepRecordings by remember { mutableStateOf(settings.keepRecordings) }
    var recordingsBytes by remember { mutableStateOf(0L) }
    var clearingRecordings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(recordings) {
        recordingsBytes = withContext(Dispatchers.IO) { recordings.size() }
    }
    var engine by remember { mutableStateOf(settings.engine) }
    var model by remember { mutableStateOf(settings.model) }
    var language by remember { mutableStateOf(settings.language) }
    val colors = MaterialTheme.colorScheme

    Text(stringResource(R.string.settings_page_voice), style = MaterialTheme.typography.headlineLarge)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.settings_engine))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            VoiceEngine.entries.forEach { option ->
                PillButton(
                    text = stringResource(
                        when (option) {
                            VoiceEngine.Whisper -> R.string.engine_whisper
                            VoiceEngine.Device -> R.string.engine_device
                            VoiceEngine.Wispr -> R.string.engine_wispr
                        }
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
                when (engine) {
                    VoiceEngine.Whisper -> R.string.engine_whisper_help
                    VoiceEngine.Device -> R.string.engine_device_help
                    VoiceEngine.Wispr -> R.string.engine_wispr_help
                }
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
    // Wispr Flow has its own language settings.
    if (engine != VoiceEngine.Wispr) SettingField(
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
                clearingRecordings = true
                scope.launch {
                    recordingsBytes = withContext(Dispatchers.IO) {
                        recordings.clear()
                        recordings.size()
                    }
                    clearingRecordings = false
                }
            },
            enabled = !clearingRecordings,
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

    Text(stringResource(R.string.settings_page_api), style = MaterialTheme.typography.headlineLarge)
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
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
                selected = true,
                enabled = keyDraft.isNotBlank(),
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

    Text(stringResource(R.string.settings_ai), style = MaterialTheme.typography.headlineLarge)
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
    Text(stringResource(R.string.settings_page_floating), style = MaterialTheme.typography.headlineLarge)
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
private fun AppearancePage(palette: AppPalette, onPaletteChange: (AppPalette) -> Unit) {
    Text(stringResource(R.string.settings_page_appearance), style = MaterialTheme.typography.headlineLarge)
    Text(
        stringResource(R.string.settings_appearance_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        AppPalette.entries.forEach { option ->
            PillButton(
                text = stringResource(
                    when (option) {
                        AppPalette.Indigo -> R.string.palette_indigo
                        AppPalette.Classic -> R.string.palette_classic
                        AppPalette.Mono -> R.string.palette_mono
                    },
                ),
                onClick = { onPaletteChange(option) },
                selected = option == palette,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    Text(stringResource(R.string.settings_page_backup), style = MaterialTheme.typography.headlineLarge)
    Text(
        stringResource(R.string.backup_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        PillButton(stringResource(R.string.backup_export), onExport, selected = true)
        PillButton(stringResource(R.string.backup_import), onImport)
    }
    if (message != null) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(stringResource(R.string.markdown_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.markdown_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (markdownFolder != null) {
        Text(stringResource(R.string.markdown_saving_to, markdownFolder), style = MaterialTheme.typography.bodyMedium)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
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
