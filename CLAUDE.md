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
5. **Voice by default**: opening a note (floating or full) starts recording unless that's
   turned off in settings. Audio goes to a cloud Whisper API and the text is added to the note.

## Tech stack
- Kotlin · Jetpack Compose + Material 3
- AGP 9.2.1 (built-in Kotlin) · Gradle 9.4.1 · Compose BOM 2026.06.00 — same as toddler-learn
- Min SDK 26 (needed for TYPE_APPLICATION_OVERLAY) · Target/Compile SDK 37 · JVM 17 · single `:app` module
- Persistence: Room (notes + blocks). Settings in DataStore.
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
  - `OverlayService`: foreground service (`specialUse`) that owns the window; notification has Show/Close.
  - `OverlayWindow`: one WindowManager window that is either the panel or the bubble; handles
    focus hand-off, opacity (`LayoutParams.alpha`), move/resize, and saves geometry via `OverlayPrefs`.
  - `OverlayUi.kt`: `OverlayBubble` and `OverlayPanel`. The panel hosts the same
    `NoteListContent` / `NoteEditorContent` as the full app.
  - `WindowDrag.kt`: drag/tap using raw screen coordinates, reported as the total offset since
    the drag started (the window is placed at start + offset, never stepped). Use it for anything that moves
    the window; Compose drag gestures jitter because the window moves under the finger.
  - `WindowGeometry.kt`: pure clamp/resize maths, unit-tested, plus `PanelSize` presets
    (S/M/L; default Small). The panel's ◐ button shows size chips and the opacity slider.
- `FloatLaunchActivity`: the "Float note" launcher entry for the Edge panel. No UI. Also asks
  for the mic on the overlay's behalf (`requestMicIntent`), since a Service can't.
- `voice/`: voice notes.
  - `VoiceController`: record → transcribe → `session.addTranscript(noteId, text)`. One per
    process (`FloatNoteApp.voice`), so bubble and editor share state. Keeps audio on failure for Retry.
  - Two engines (`VoiceEngine`, chosen in Settings): Whisper (record, then upload) and Device
    (`AndroidLiveRecognizer`: the phone's default recognition service first, the "on-device"
    recognizer API only as fallback; switches if one hears speech but returns empty text;
    restarted after each pause for continuous dictation; sentences land in the note as heard).
  - `MediaAudioRecorder` (16 kHz mono AAC .m4a, live level), `WhisperClient` (OpenAI-compatible
    `/audio/transcriptions`, works with OpenAI or Groq), `VoiceSettings` (key encrypted with a
    Keystore AES-GCM key; base URL, model, language, auto-record).
  - `VoiceUi.kt`: `AutoRecordEffect` (start when a note opens, stop + send when it closes),
    `VoiceStatus` strip, `Waveform`, `AppVoiceActions`.
- `ui/settings/SettingsContent.kt`: voice settings screen in the full app.

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
  Opacity = `LayoutParams.alpha`.
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
- Never name a composable `Content()` and call it inside `ComposeView(...).apply { setContent { } }`:
  it resolves to `ComposeView.Content()` and recurses until the app crashes (StackOverflowError).
- Testing on a device: get coordinates from `adb shell uiautomator dump` or `dumpsys input`
  (window frames), never from a guess. A missed tap lands on whatever app is underneath.
- Edge panel entry = an `activity-alias` with its own label/icon and a translucent,
  no-UI Activity that starts the overlay service and immediately `finish()`es.

## Secrets
- The Whisper API key is entered in-app and stored encrypted (Android Keystore AES key +
  DataStore). Never hardcode it, log it, or commit it. Release signing secrets go in
  `~/.gradle/gradle.properties`.

## Change safety
- Smallest change that solves the problem. Match surrounding style. No drive-by refactors.
- Verify a symbol/file exists before referencing it. Don't invent APIs.
- Don't commit, push, or open PRs unless asked. Never commit `graphify-out/`.
- Record shortcuts and limits in `KNOWN_ISSUES.md`.

## Tooling
- graphify CLI is installed but no graph is built yet. Run `graphify .` once there's
  code to enable graph-aware debugging and query-first navigation.
