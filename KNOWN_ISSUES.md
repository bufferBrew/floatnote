## 2026-10-24 — Saves rewrite every block
- What changed / what shortcut was taken: `NoteDao.replace` deletes and re-inserts all of a note's blocks on each (debounced) save, instead of diffing.
- Ceiling (when this breaks): notes with thousands of blocks, where a save takes long enough to drop frames or drain battery.
- Upgrade trigger (what to do when it's hit): diff old vs new block lists and upsert/delete only what changed.
- Status: open

## 2026-10-24 — Only one note, no list screen
- What changed / what shortcut was taken: `MainActivity` always opens the most recently edited note (or creates one). There is no way to browse, create or delete notes yet.
- Ceiling (when this breaks): as soon as a second note is needed.
- Upgrade trigger (what to do when it's hit): add a note list screen and pass a note id into `NoteViewModel`.
- Status: resolved (2026-10-24, `NoteListContent` + app-wide session; the app opens on the list)

## 2026-10-24 — New task inside the done section in move-to-bottom mode
- What changed / what shortcut was taken: pressing Enter in (or adding after) a done task inserts an open task among the done ones. It stays there until the next toggle or mode switch reorders the group.
- Ceiling (when this breaks): a user editing a completed task and pressing Enter sees an open task below done ones.
- Upgrade trigger (what to do when it's hit): in `NoteEditor.insertAfter`, place new open tasks after the last open task of the group when the mode is MoveToBottom.
- Status: resolved (2026-10-24, `NoteEditor` re-sorts task groups after every insert, convert and remove in MoveToBottom mode)

## 2026-10-24 — Keyboard can cover the floating panel
- What changed / what shortcut was taken: overlay windows aren't resized or pushed up when the keyboard opens. The panel defaults to the upper part of the screen to avoid it, but a panel dragged low can end up under the keyboard.
- Ceiling (when this breaks): writing in a panel placed in the bottom half of the screen.
- Upgrade trigger (what to do when it's hit): listen for IME insets on the overlay root (`WindowInsetsCompat.Type.ime()`) and temporarily move the panel up while the keyboard is showing.
- Status: open

## 2026-10-24 — Notification permission isn't requested
- What changed / what shortcut was taken: the app declares POST_NOTIFICATIONS but never asks for it. On Android 13+ the foreground-service notification (with Show/Close) stays hidden until the user allows notifications in Settings. The overlay itself works either way.
- Ceiling (when this breaks): a user who closes the bubble's app and wants the notification to bring the note back.
- Upgrade trigger (what to do when it's hit): request POST_NOTIFICATIONS the first time the user taps Float.
- Status: resolved (2026-10-24, asked once from FloatLaunchActivity and from the app's Float button)

## 2026-10-24 — Overlay not yet tested on a device
- What changed / what shortcut was taken: the overlay (focus hand-off, drag, resize, opacity, edge-panel entry) is built and unit-tested where it's pure logic, but hasn't been run on a phone or emulator. None is set up on the dev Mac.
- Ceiling (when this breaks): any device-specific behaviour, especially Samsung One UI and the Edge panel.
- Upgrade trigger (what to do when it's hit): install the debug APK on the phone, or set up an emulator, and go through the overlay by hand.
- Status: resolved (2026-10-24, checked over adb on a Galaxy S25 Ultra, Android 17: launch, panel drag, typing, checkbox, focus hand-off, opacity, minimise, bubble drag and edge clamp, tap to reopen. Found and fixed a crash on open and a status-bar offset in window positions.)

## 2026-10-24 — Window edges on Android 8–10
- What changed / what shortcut was taken: the usable area (screen minus status/navigation bars) is read from `WindowMetrics`, which only exists on Android 11+. On 8–10 the full screen size is used, so the panel or bubble can be dragged under the status bar.
- Ceiling (when this breaks): anyone on Android 10 or older.
- Upgrade trigger (what to do when it's hit): read the status/navigation bar heights from the root view's `WindowInsets` on older versions.
- Status: open

## 2026-10-24 — Wispr Flow look is inferred, not copied
- What changed / what shortcut was taken: Wispr Flow blocks screenshots of its UI, so the style (monochrome palette, serif titles, pill chips, dark pill bubble with a waveform) is based on its public look rather than measured against it.
- Ceiling (when this breaks): if it doesn't feel close enough to Wispr Flow next to the real thing.
- Upgrade trigger (what to do when it's hit): compare against screenshots the user takes (Wispr blocks adb captures) and adjust `Theme.kt` and `OverlayUi.kt`.
- Status: open

## 2026-10-24 — Voice transcription not yet tried end to end on the phone
- What changed / what shortcut was taken: recording, upload and error handling are unit-tested (fake recorder, MockWebServer for the Whisper API), and on the phone the no-key flow, settings and mic prompt were checked. A real recording → Whisper → note run needs the user's API key and mic permission, which only the user can enter and grant.
- Ceiling (when this breaks): anything device- or account-specific: audio source quality, the provider's limits, background mic access on One UI.
- Upgrade trigger (what to do when it's hit): after the key is saved and the mic allowed, record a note from the floating window over another app and check the transcript lands in the right note.
- Status: open

## 2026-10-24 — Transcripts always go to the end of the note
- What changed / what shortcut was taken: each transcript is appended as a new text line at the bottom, not at the cursor.
- Ceiling (when this breaks): dictating into the middle of a long note.
- Upgrade trigger (what to do when it's hit): insert after the focused block when the note is open and something is focused.
- Status: open

## 2026-10-24 — Closing the floating window mid-recording
- What changed / what shortcut was taken: ✕ stops the recording and starts the upload, then the service stops. If Android kills the process before the upload finishes, that clip is lost (the file is in the cache dir and is not retried on next launch).
- Ceiling (when this breaks): closing right after a long recording on a slow connection.
- Upgrade trigger (what to do when it's hit): keep the service alive until the upload finishes, or retry leftover clips on next start.
- Status: open

## 2026-10-24 — 10-minute recording cap
- What changed / what shortcut was taken: a recording stops itself and uploads after 10 minutes, keeping files well under the Whisper API's 25 MB upload limit (~3.6 MB at 48 kbps).
- Ceiling (when this breaks): dictating longer than 10 minutes in one go.
- Upgrade trigger (what to do when it's hit): split long recordings into chunks and transcribe them in order.
- Status: open

## 2026-10-24 — Device speech engine restarts between sentences
- What changed / what shortcut was taken: Android's recognizer ends at each pause, so `AndroidLiveRecognizer` restarts it after every sentence. A few hundred milliseconds of speech right at a restart can be missed, and some recognizers play a start/stop tone on each restart. Android 13's segmented sessions would avoid this but aren't used yet.
- Ceiling (when this breaks): fast talkers who don't pause, or anyone bothered by the tones.
- Upgrade trigger (what to do when it's hit): on API 33+, use `RecognizerIntent.EXTRA_SEGMENTED_SESSION` with `onSegmentResults`/`onEndOfSegmentedSession`.
- Status: open

## 2026-10-24 — Device engine not yet tried on the phone (now tried)
- What changed / what shortcut was taken: the controller logic is unit-tested with a fake recognizer, but the real `SpeechRecognizer` (offline model availability, background use from the floating window, Samsung vs Google recognizer) hasn't been run on the S25 Ultra yet.
- Ceiling (when this breaks): the phone's recognizer refusing to run while another app is in front, or having no offline model for the chosen language.
- Upgrade trigger (what to do when it's hit): pick "This phone" in Settings, dictate from the floating window over another app, and read the log.
- Status: resolved (2026-10-24, dictated over a video app on the S25 Ultra; sentences landed in the note; mic not silenced in the background)

## 2026-10-24 — Guards against the app getting stuck
- What changed / what shortcut was taken: user reported the app "gets stuck at times". No crash or ANR was in the phone's log, so the fixes target every wait that could look frozen: Whisper uploads now have a 90 s overall limit and a Cancel button (audio kept for Retry); the device recognizer has an 8 s watchdog that reconnects a silent speech service (max 3 times in a row); window drags are measured from the drag start so the window can't fall out of step with the finger at a screen edge; the panel defaults to small (260×300 dp) so it covers less of the app underneath.
- Ceiling (when this breaks): a stuck state not covered above.
- Upgrade trigger (what to do when it's hit): ask what was on screen when it stuck, capture `adb logcat` (tag FloatNoteVoice) and `dumpsys window`, and add a guard for that case.
- Status: resolved (2026-10-24). The real cause of "non-responsive" was found in the phone's input log: the opacity slider set window alpha, and Android drops all touches on windows below its alpha threshold. Opacity is now drawn as content alpha. CPU while listening also dropped from ~50% to ~5–10% (waveform drawn in one Canvas pass at 10 Hz, voice state read only where drawn).

## 2026-10-24 — Floating panel touch targets are 36 dp
- What changed / what shortcut was taken: to make the panel thin, its buttons, checkboxes and chips use 36 dp touch targets instead of the recommended 48 dp (`LocalMinimumInteractiveComponentSize` in `OverlayPanel`). The full app keeps 48 dp.
- Ceiling (when this breaks): users who find the small targets hard to hit.
- Upgrade trigger (what to do when it's hit): add a "comfortable" density option that restores 48 dp in the panel.
- Status: open

## 2026-10-24 — Google's on-device recognizer returns empty text
- What changed / what shortcut was taken: on the S25 Ultra, `createOnDeviceSpeechRecognizer` runs Google's offline engine in an ambient one-shot mode that detects speech but returns empty transcripts. The app now uses the phone's default recognition service (Google's dictation service, still offline with a language pack) and only falls back to the on-device API; it switches automatically after two empty-after-speech results.
- Ceiling (when this breaks): phones whose default recognizer needs the network and whose on-device one also returns nothing.
- Upgrade trigger (what to do when it's hit): surface which recognizer is in use in Settings and let the user pick.
- Status: open

## 2026-10-24 — History, vocabulary, AI clean-up/Organise, note switcher
- What changed / what shortcut was taken: dictation history (Room v2, auto-migration from v1), a
  vocabulary, an optional AI clean-up of each dictation, ✦ Organise for a whole note, and a quick
  note switcher in the floating panel. The AI uses the same key and server as Whisper, with
  `gpt-4o-mini` as the default chat model. Built and unit-tested (89 tests), **not yet run on a
  device** (the phone was disconnected).
- Ceiling (when this breaks):
  - Clean-up and Organise send note text to the configured API provider.
  - Clean-up adds one network round trip per dictation; with the device engine that's one per
    sentence, so text lands a moment later.
  - Organise Undo lasts only until the note closes.
  - An edit made while Organise is running discards its result.
  - History keeps the newest 500 dictations.
  - Device-recognizer hints need Android 13+ and a recognition service that honours them;
    vocabulary fixes after recognition always apply.
  - (Fixed the same day: long-press delete is now immediate with an Undo bar, no dialog.)
- Upgrade trigger (what to do when it's hit):
  - Organise previews or a version history if users want to compare.
  - Batch device sentences before clean-up if the latency bothers users.
  - Move the list's delete confirm inline if it crashes in the overlay.
  - Add a Room `MigrationTestHelper` test for 1→2.
- Status: open

## 2026-10-24 — Improvement backlog P1 items (#2–#14 in IMPROVEMENTS.md)
- What changed / what shortcut was taken:
  - Launching: Quick Settings tile, app shortcuts ("Floating note", "New floating note"), and a
    "Float note opens a new note" setting.
  - Sharing: "Share to FloatNote" makes a new floating note; ✦ Share sends a note out.
  - Overlay Back on Android 13+ uses `OnBackInvokedCallback`.
  - Recording stops after 45 s of silence. A Whisper clip with no speech at all is dropped,
    not uploaded.
  - API key: kept in its own `voice_secret` prefs file, excluded from backup. It's only sent to
    https servers, and only to the host it was saved for until you allow a new one.
  - Long-press delete with Undo (8 s).
  - The editor scrolls to new lines and follows dictation when you're at the end.
  - Checkboxes are labelled for TalkBack.
  - Robolectric Room tests: the 1→2 migration and the DAOs.
  - Release signing from `FLOATNOTE_*` properties; the version lives in gradle.properties.
  - All built and unit-tested (102 tests); not yet run on the phone.
- Ceiling (when this breaks):
  - The silence thresholds (`RECORDER_QUIET` 0.05, `LIVE_QUIET` 0.3) are estimates. A noisy room
    may keep recording; a very soft voice may stop early.
  - Undo after delete lives in memory only: the process dying in those 8 s loses it.
  - Excluding `voice_secret` means the key must be re-entered on a new phone (it couldn't be
    decrypted there anyway). Other voice settings still back up.
  - Schemas ship inside debug APKs (assets) so Robolectric can read them.
- Upgrade trigger (what to do when it's hit):
  - Tune the thresholds from real recordings, or expose them as a setting.
  - Move to a soft-delete column (Room v3 + migration test) if the in-memory Undo isn't enough.
- Status: open (needs a device check)

## 2026-10-24 — Backlog P2/P3 items, Settings pages, Dictionary page
- What changed / what shortcut was taken:
  - Settings is now a menu of pages (Voice, API key and server, AI, Floating note, Backup).
    Dictionary and History are pages of their own beside the notes, in the app and the floating panel.
  - New: search, pinning (Room v3, auto-migration, tested), JSON export/import, undo/redo, and
    Backspace on an empty line. Device dictation joins into one paragraph per recording.
  - Also: string resources for errors, `RecognizerPolicy`, window context, contrast and TalkBack
    fixes, CI workflow, privacy docs, History "Use what was heard".
  - 128 unit tests pass. **Not run on a device.**
- Ceiling (when this breaks):
  - Undo history is in memory and per open note (lost on close), capped at 100 steps; quick
    typing in one line within 1.5 s is one step.
  - Backspace-to-remove depends on the keyboard sending a key event for an empty field; most do
    (Gboard, Samsung), some may not.
  - Importing creates new notes every time, so importing the same file twice duplicates.
  - "Use what was heard" swaps the newest matching text in the note; if you edited that sentence
    since, nothing happens.
  - Search uses LIKE over every block, which is fine for thousands of notes; FTS would be needed
    beyond that.
  - CI compiles against SDK 37, which the runner downloads; not yet run on GitHub.
- Upgrade trigger (what to do when it's hit):
  - Room FTS4 if search gets slow.
  - Persist undo per note if people want it after reopening.
  - Organise preview (#33).
- Status: open (needs a device check)

## 2026-10-24 — Auto-record only trusts launches the user made
- What changed / what shortcut was taken: `FloatLaunchActivity` is exported (launcher, Edge panel, share target), so any app could open it and, with auto-record on, switch the mic on. Now auto-record skips the note opened at launch unless `getReferrer()` (with no `EXTRA_REFERRER*` extras) is FloatNote, a system app or the default home app. Shared text never auto-records. Tapping Record always works.
- Ceiling (when this breaks): a third-party launcher or edge app that isn't the default home and isn't preinstalled opens the note without auto-recording. Preinstalled apps are trusted as a group.
- Upgrade trigger (what to do when it's hit): if users of other launchers ask, add a "trusted launchers" list in Settings, or check `getLaunchedFromPackage()` on Android 14+.
- Status: open

## 2026-10-25 — A clip left by a killed process is offered for Retry, even if it's cut off
- What changed / what shortcut was taken: clips are named `clip-<time>-<noteId>.m4a`, and `VoiceController` offers the newest leftover one for Retry at startup (older ones are deleted). It can't tell a clip that was uploading (complete) from one cut off mid-recording (MediaRecorder never finished the file).
- Ceiling (when this breaks): after a kill during recording, Retry sends an unreadable file and Whisper rejects it; the user dismisses the error.
- Upgrade trigger (what to do when it's hit): rename a clip to `kept-…` once its recording stops, and only restore those.
- Status: open

## 2026-10-25 — Record pill (panel shrunk to one button) not yet tried on a device
- What changed / what shortcut was taken: the corner now resizes down to 44 dp; below 180×160 dp the panel shows only a record pill. The pill has no resize grip (it would cover the button): long-press restores the last full size instead. While recording, the window widens to 136 dp for ✕ / waveform / ✓.
- Ceiling (when this breaks): if long-press feels hidden, users may not find their way back from the pill (the notification's Show does not restore the size).
- Upgrade trigger (what to do when it's hit): a first-time hint ("Long-press for the full note") the first time the pill appears.
- Status: open (needs a device check: shrink through the threshold, long-press restore, ✕/✓, move)

## 2026-10-25 — Note categories and context-aware ✦ Organise
- What changed / what shortcut was taken: four fixed categories (an enum, not user-defined). Each has a context text, editable in Settings → AI, with built-in defaults that name the Brew apps and the other GitHub projects as of today. Organise sends that text plus up to 15 titles of other notes in the category; for a note with no category it sends every category's text so the model can pick one. Suggested lines are plain text blocks marked `✦ `, removed only by Organise's Undo or by hand.
- Ceiling (when this breaks): the default project lists go stale as projects are renamed or retired; wanting a fifth category means a code change. Long context texts add tokens to every Organise.
- Upgrade trigger (what to do when it's hit): user-defined categories (a table, with a migration), or a "✦" block type the UI can style and clear in one tap.
- Status: open (not yet tried on a device or against a real AI server)

## 2026-10-27 — Bubble tap now records; Test connection
- What changed / what shortcut was taken: tapping the minimised bubble starts listening (tap again stops and adds it), and long-press opens the panel, matching the record pill. Minimising makes sure a note is open (the most recent one) so there's somewhere to record into. Settings' Test connection checks the speech model against the server's `/models` list; a server without that list shows "Not checked" for it.
- Ceiling (when this breaks): opening the panel is now a long-press, which may not be obvious; the notification's Show still opens it. A server whose `/models` omits a working model shows a false "Not offered".
- Upgrade trigger (what to do when it's hit): a first-time hint on the bubble; or test the speech model with a short silent clip instead of the model list.
- Status: open (not yet tried on a device)
