# FloatNote — Project Memory

Android note-taking app built to be used *on top of* other apps. A floating, adjustable-opacity
note window, a minimised draggable bubble, and voice capture that starts by default.
Not a Brew product: don't apply brew/AGENTS.md rules or rename to <Thing>Brew.
Solo developer, main-branch workflow.

## Product requirements (source of truth)
1. Note content is a list of blocks:
   - **Text**: plain paragraph.
   - **Checklist item**: checking it strikes the text through. It stays in place.
   - **Task item**: a checkbox with a per-note "on complete" mode:
     **Move to bottom** or **Keep in place**. "Bottom" means the end of the task's group
     (adjacent tasks), not the end of the note. Logic: `domain/NoteEditor.kt`.
2. **Floating mode**: a window drawn over other apps (no split screen, no leaving the
   current app), with an opacity slider.
3. **Feature parity**: everything in the full app (editing, all block types, both task
   modes, voice, opacity) must work in floating mode too. No floating-only gaps.
4. **Quick launch**: a separate launcher entry "Float note" that can be added to the
   Samsung Edge panel and opens the floating note straight away. Also a Quick Settings tile
   and an app shortcut. The window minimises to a small bubble you can drag anywhere;
   the bubble's position is remembered.
5. **Voice on tap**: recording starts when you tap ● Record (floating or full). Auto-record on
   note open is an opt-in setting, off by default (changed 2026-10-24 at the user's request).
   Speech goes to a cloud Whisper API or the phone's recognizer, and the text is added to the note.

## Tech stack
- Kotlin · Jetpack Compose + Material 3
- AGP 9.2.1 (built-in Kotlin) · Gradle 9.4.1 · Compose BOM 2026.06.00 — same as toddler-learn
- Min SDK 26 (needed for TYPE_APPLICATION_OVERLAY) · Target/Compile SDK 37 · JVM 17 · single `:app` module
- Persistence: Room v5 (notes with `pinned` and `category`, blocks, dictation history with its kept
  recording's file name, vocabulary). Settings in
  SharedPreferences. Robolectric tests (`data/db/DatabaseTest`) run every migration.
- Overlay: a foreground service that adds a `ComposeView` to `WindowManager`
  (`TYPE_APPLICATION_OVERLAY`). The service supplies its own Lifecycle /
  SavedStateRegistry / ViewModelStore owners so Compose works outside an Activity.
- Voice: `MediaRecorder` (AAC/m4a) → `POST /v1/audio/transcriptions` (OpenAI-compatible;
  the base URL can be changed so Groq etc. work too). HTTP via OkHttp.
- Tests: JUnit 4 + MockK, pure JVM unit tests

## Build / test / run (macOS)
| Task        | Command                    |
|-------------|----------------------------|
| Run tests   | `./gradlew testDebugUnitTest` |
| Debug build | `./gradlew assembleDebug`  |
| Install     | `./gradlew installDebug`   |
| Lint        | `./gradlew lint`           |
| Release APK | `./gradlew assembleRelease` (unsigned without the signing properties) |
| CI          | `.github/workflows/ci.yml`: unit tests, lint, release build |
| APK to Drive | Automatic: `.githooks/post-commit` builds the debug APK after each commit and copies it to `My Drive/apk/FloatNote-debug.apk` (log: `build/apk-to-drive.log`). Enable on a new clone with `git config core.hooksPath .githooks` |

Run `./gradlew testDebugUnitTest` and `assembleDebug` before calling a change done.

## Code map
- `domain/model/Note.kt`: `Note`, `Block` (Text / Checklist / Task), `TaskCompletionMode`.
- `domain/NoteEditor.kt`: every edit, as pure functions. Unit-tested.
- `data/db/`: Room `notes` + `blocks` tables (blocks cascade-delete). A save rewrites all of
  a note's blocks in one transaction. Schema JSON is exported to `app/schemas/`; commit it.
- `data/NoteMapper.kt`, `data/NoteRepository.kt`: entity ↔ domain, `RoomNoteRepository`.
- `FloatNoteApp`: manual DI root (`repository`, `persistScope`, `session`). `session` is the one
  app-wide `NoteViewModel`, so the full app and the overlay edit the same in-memory note.
- `ui/editor/NoteViewModel.kt`: the session (note list + open note). Holds the open note as Compose snapshot state (not a Flow, so
  text fields don't lose the cursor), with a debounced save plus `flush()` on stop/clear.
- `ui/editor/NoteEditorContent.kt`: the stateless editor, driven through `NoteEditorActions`.
- `ui/list/NoteListContent.kt`: the stateless note list (long-press to delete, with a confirm).
  The app opens on the list. A note left blank is deleted on close instead of saved.

- `overlay/`: the floating window.
  - `OverlayService`: foreground service (`specialUse`) that owns the window; notification has Show/Close,
    and while recording the time plus Pause / Resume / Stop and add (reposted only when the phase changes).
  - `OverlayWindow`: one WindowManager window that is either the panel or the bubble; handles
    focus hand-off, opacity (content alpha, never window alpha), move/resize, and saves geometry via `OverlayPrefs`.
  - `OverlayUi.kt`: `OverlayBubble`, `OverlayPanel` and `OverlayRecordPill`. The panel hosts the same
    `NoteListContent` / `NoteEditorContent` as the full app. The corner resizes freely down to one
    button; below 180×160 dp the panel becomes the record pill (tap records, ✕/✓ while recording,
    long-press restores the last full size, kept in `OverlayPrefs.fullPanel`). The minimised bubble
    works the same way: tap records, long-press opens the panel (a tap opens it while transcribing
    or after an error). While recording, both widen into one `RecordingBar`: ✕, ❚❚/▶ (Whisper
    clips only), the waveform (drag; tap stops in the bubble) and ✓.
  - `WindowDrag.kt`: drag/tap using raw screen coordinates, reported as the total offset since
    the drag started (the window is placed at start + offset, never stepped). Use it for anything that moves
    the window; Compose drag gestures jitter because the window moves under the finger.
  - `WindowGeometry.kt`: pure clamp/resize maths, unit-tested, plus `PanelSize` presets
    (S/M/L; default Small). The panel's ◐ button shows size chips and the opacity slider.
- `FloatLaunchActivity`: the "Float note" launcher entry for the Edge panel, the QS tile, the
  shortcuts and "Share to FloatNote" (shared text becomes a new note). No UI. Also asks
  for the mic on the overlay's behalf (`requestMicIntent`), since a Service can't.
- `voice/`: voice notes.
  - `VoiceController`: record → transcribe → `session.addTranscript(noteId, text)`. One per
    process (`FloatNoteApp.voice`), so bubble and editor share state. Keeps audio on failure for Retry.
    A Whisper clip pauses for a call or alarm and resumes in the same clip (`AudioInterruptions`:
    transient audio focus); the user can pause too (status strip, notification). It also transcribes
    audio shared to the app (`transcribeFiles`, a queue; `FloatLaunchActivity` copies the files,
    `SharedAudio` picks the extension the server reads the format from). Long clips are recorded as
    back-to-back 5 MB files (`MediaRecorder.setNextOutputFile`, no gap) and sent in order, up to 2 hours.
    If Whisper can't be used (no key, a settings problem, offline) a new recording falls back to the
    phone's recognizer (`VoiceState.Recording.fallback`).
  - Kept recordings: after transcription a clip goes to `data/RecordingStore` (`files/recordings`,
    300 MB cap, not backed up) and its name to `dictations.audio`; History plays it or sends it again
    (`FloatNoteApp.transcribeAgain`). Off with Settings → Voice → Keep recordings.
  - Two engines (`VoiceEngine`, chosen in Settings): Whisper (record, then upload) and Device
    (`AndroidLiveRecognizer`: the phone's default recognition service first, the "on-device"
    recognizer API only as fallback; switches if one hears speech but returns empty text;
    restarted after each pause for continuous dictation; sentences land in the note as heard).
  - `MediaAudioRecorder` (16 kHz mono AAC .m4a, live level), `WhisperClient` (OpenAI-compatible
    `/audio/transcriptions`, works with OpenAI or Groq), `VoiceSettings` (key encrypted with a
    Keystore AES-GCM key; base URL, model, language, auto-record).
  - `VoiceUi.kt`: `AutoRecordEffect` (start when a note opens, stop + send when it closes),
    `VoiceStatus` strip, `Waveform`, `AppVoiceActions`.
  - `TranscriptPipeline`: everything heard goes through it, one at a time in order: vocabulary
    fixes → optional AI clean-up (falls back to the uncleaned text) → note → history.
- `domain/Vocabulary.kt`: the Wispr-style dictionary. Fixes spellings / applies replacements after
  recognition, and feeds hints to Whisper (`prompt`) and the device recognizer (`EXTRA_BIASING_STRINGS`, 13+).
- `data/VoiceRepository.kt` + `data/db/VoiceDao.kt`: dictation history (capped at 500) and vocabulary
  (Room v2, added by `AutoMigration(1, 2)`).
- `ai/`: the AI step, OpenAI-compatible `/chat/completions` on the same key and server as Whisper.
  `NoteAssistant.cleanUp` (per dictation, opt-in), `organise` (✦ Organise in the editor) and
  `summarise` (✦ Summarise: a `✦ Summary:` line on top plus open action items as tasks, same Undo);
  `NoteFormat` is the pure JSON in/out with validation (a bad reply never replaces the note).
  The session (`NoteViewModel.onOrganise`) keeps lines dictated meanwhile, drops the result if you
  edited, and offers Undo until the note closes. Organise is given the note's category context
  (`ai/CategoryContext`, editable in Settings → AI) and the titles of other notes in that category;
  it fills an empty category and, when `VoiceSettings.aiSuggestions` is on, adds up to 3 lines
  marked `✦ ` (`NoteFormat.ADDED_MARK`).
- `domain/model/NoteCategory.kt`: Work / Personal / Brew projects / Personal GitHub. Stored as its
  `key` in `notes.category` (Room v4, `AutoMigration(3, 4)`); never rename a key. The list filters by
  it (`NoteViewModel.categoryFilter`, chips in `ui/components/CategoryChips.kt`); the editor sets it
  from the chip under the title.
- `ui/history/HistoryContent.kt`: dictation history (copy, open note, delete, clear), in both hosts.
  The floating panel also has a quick note switcher: tap the title (▾) for a row of recent notes.
- `ui/settings/SettingsContent.kt`: Settings as a menu of pages (`SettingsPage`: Voice, API key and
  server, AI, Floating note, Backup) in the full app. The API and AI pages have "Test connection"
  (`net/ConnectionCheck`: `GET /models` for the key and speech model, one tiny chat call for the AI
  model). Use `AppSwitch` (`ui/components/Toggle.kt`), not Material's `Switch`: its off state
  draws in our near-invisible `outline` colour. The Dictionary (`ui/dictionary/`) and History
  are pages of their own beside the notes, in both hosts.
- Notes list: search (`NoteViewModel.query` → `searchResults`), long-press for Pin / Delete (Undo bar).
  The session also keeps undo/redo per open note (`onUndo` / `onRedo`).
- `domain/NotesBackup.kt` + `data/NotesBackup.kt`: JSON export/import via the Storage Access Framework.
- Markdown folder (Settings → Backup): `domain/NoteMarkdown` renders a note; `data/MarkdownExport` writes
  one `.md` per note to a SAF folder; `ExportingNoteRepository` wraps the repository and updates a note's
  file 3 s after its last save (rename follows the title, delete removes the file).
- `util/Strings.kt`: user-facing text for classes without a Context. Every error message lives in
  strings.xml; JVM tests use `TestStrings`, which reads the real file.
- `voice/RecognizerPolicy.kt`: the device recognizer's error/reconnect/switch decisions, pure and tested.

## Architecture guardrails
- Note editor UI is **one composable used by both the Activity and the overlay**:
  `NoteEditorContent` + `NoteViewModel`. Never fork editor logic for floating mode; that's
  how parity breaks.
- A schema change means bumping the Room `version` and adding a migration. Never use
  destructive fallback: these are the user's notes.
- Block ordering rules (task "move to bottom" vs "keep in place") live in pure Kotlin
  domain code with unit tests, not in composables.
- Overlay window: drop `FLAG_NOT_FOCUSABLE` while a text field is focused so the
  keyboard (incl. Wispr Flow) works, and restore it when minimised so touches pass through.
  Opacity is a graphics layer on the content (see the gotcha below), never `LayoutParams.alpha`.
- Use theme tokens. No hardcoded colors or dp literals for corners and elevation.
- Visual style follows Wispr Flow: monochrome ink on warm off-white (near-black in dark mode),
  serif titles, pill chips (`ui/components/Buttons.kt`), large rounded cards, no accent colour.
  The bubble is a small dark pill with a waveform and stays dark in both themes (`BubbleColors`).

## Platform gotchas (check before touching overlay/voice/launch code)
- `SYSTEM_ALERT_WINDOW` is granted by the user in Settings. Detect it with
  `Settings.canDrawOverlays` and guide the user there.
- The mic foreground service needs `foregroundServiceType="microphone"` plus
  `FOREGROUND_SERVICE_MICROPHONE` (Android 14+). Microphone FGS while-in-use access
  requires the start to come from a visible UI: launch it from the trampoline Activity /
  tile click, never from a background broadcast.
- The overlay service declares `specialUse|microphone`; it adds the microphone type only when
  RECORD_AUDIO is granted (declaring it without the permission throws). Started from a visible
  Activity, that keeps background mic access while another app is in front.
- Activities (including the permission prompt) draw *under* overlay windows: minimise the
  overlay before opening one (`AppVoiceActions(beforeOpeningScreen = …)`).
- The `<queries>` entry for `android.speech.RecognitionService` is required: without it,
  Android 11+ hides the recognizer and `SpeechRecognizer.isRecognitionAvailable` returns false.
- QS tile on Android 14+: use `startActivityAndCollapse(PendingIntent)`.
- **Never set `LayoutParams.alpha` on the overlay.** Android drops every touch on a window whose
  alpha is below its threshold ("Not sending motion … window opacity below the threshold"), so a
  faded panel goes dead. Fade the content with `graphicsLayer { alpha = … }` instead.
- Don't read fast-changing state (voice levels, ~10×/s) high in the overlay tree: pass it as a
  lambda and read it in the leaf that draws it, or the whole panel recomposes. The waveform is a
  single Canvas with no per-bar animation; animating bars kept it at 60 fps and ~50% CPU.
- `uiautomator dump` returns nothing while the overlay is animating (it waits for idle). Locate
  controls from the window frame (`dumpsys input`) and the layout instead.
- Overlay windows need `FLAG_LAYOUT_IN_SCREEN` (+ cutout mode ALWAYS on API 30+). Without it
  Android offsets them below the status bar, so x/y aren't screen coordinates. Clamp to
  `usableArea()` (screen minus system bars and cutout), not the raw screen size.
- Don't open Compose `AlertDialog`/`Dialog` from content the overlay shows: the panel lives in a
  service window with no Activity token. Confirm inline instead (see `HistoryContent`).
- Never name a composable `Content()` and call it inside `ComposeView(...).apply { setContent { } }`:
  it resolves to `ComposeView.Content()` and recurses until the app crashes (StackOverflowError).
- Testing on a device: get coordinates from `adb shell uiautomator dump` or `dumpsys input`
  (window frames), never from a guess. A missed tap lands on whatever app is underneath.
- Edge panel entry = a second LAUNCHER activity (`FloatLaunchActivity`) with its own label/icon,
  translucent and no-UI: it starts the overlay service and immediately `finish()`es. The QS tile
  (`overlay/FloatTileService`), the app shortcuts (`res/xml/shortcuts.xml`) and the share target
  all go through it, so the service always starts from a visible Activity.
- Overlay Back: targetSdk 36+ means predictive back, so KEYCODE_BACK no longer arrives on 13+.
  `OverlayRootView` registers an `OnBackInvokedCallback` (and keeps the key path for older phones).

## Secrets
- The API key (Whisper and AI) is entered in-app and stored encrypted (Android Keystore AES key)
  in the `voice_secret` prefs file, which is excluded from backup (`res/xml/*_rules.xml`). It's
  only sent to https servers, and only to the host it was saved for until the user allows a new
  one. Never hardcode it, log it, or commit it.
- Release signing: `FLOATNOTE_KEYSTORE_PATH`, `FLOATNOTE_STORE_PASSWORD`, `FLOATNOTE_KEY_ALIAS`,
  `FLOATNOTE_KEY_PASSWORD` in `~/.gradle/gradle.properties`. Without them `assembleRelease` builds an
  unsigned APK. The version is `VERSION_CODE` / `VERSION_NAME` in the project's gradle.properties.

## Change safety
- Smallest change that solves the problem. Match surrounding style. No drive-by refactors.
- Verify a symbol/file exists before referencing it. Don't invent APIs.
- Don't commit, push, or open PRs unless asked. Never commit `graphify-out/`.
- Record shortcuts and limits in `KNOWN_ISSUES.md`.

## Tooling
- graphify CLI is installed but no graph is built yet. Run `graphify .` once there's
  code to enable graph-aware debugging and query-first navigation.
