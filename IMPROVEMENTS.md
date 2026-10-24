# FloatNote: improvement backlog (2026-10-24)

From a read-only review of the whole app. Paths are relative to `app/src/main/java/com/bufferbrew/floatnote/`.

- **Priority:** P0 must fix, P1 should, P2 good to have, P3 nice to have.
- **Effort:** S under 2 hours, M half a day to a day, L several days.

| # | Improvement | Area | Priority | Effort | Status / evidence |
|---|---|---|---|---|---|
| 1 | A server address without `https://` crashed the app when sending a recording | Reliability | P0 | S | **Fixed 2026-10-24.** Bad addresses now show an error, and the AI client does the same (`voice/WhisperClient.kt`, `ai/ChatClient.kt`) |
| 2 | Quick Settings tile and app shortcut (CLAUDE.md requirement 4) | Requirements | P1 | M | **Done 2026-10-24** (tile + 2 shortcuts). No `TileService` and no `shortcuts.xml` in the manifest |
| 3 | Back may not minimise the panel on targetSdk 37 (predictive back): register `OnBackInvokedCallback` | Android versions | P1 | S | **Done 2026-10-24** (OnBackInvokedCallback). `overlay/OverlayRootView.kt` only handles KEYCODE_BACK. Not checked on device |
| 4 | Stop recording after 30–60 s of silence | Battery, cost, privacy | P1 | M | **Done 2026-10-24** (45 s silence stop; silent clips dropped). Auto-record plus the 10-minute cap means long, billed, silent uploads |
| 5 | Keep the encrypted key out of backup, or make `hasApiKey` mean "can decrypt" | Security | P1 | S | **Done 2026-10-24** (key in its own file, excluded from backup; "saved" means decryptable). The Keystore key doesn't restore with the backup, so Settings shows "saved" but recording fails |
| 6 | Only allow `https` addresses, and confirm before sending the key to a new host | Security | P1 | S | **Done 2026-10-24** (https only + confirm new host). `voice/VoiceSettings.kt` accepts any address |
| 7 | "Share to FloatNote" (ACTION_SEND), plus share or copy a note | Product | P1 | M | **Done 2026-10-24** (share in and out). No SEND intent filter |
| 8 | Let the Float launcher open a new note | Product | P1 | S | **Done 2026-10-24** (setting + "New floating note" shortcut). `ensureOpen()` always reopens the last-edited note |
| 9 | Undo after deleting a note, or a trash | Data safety | P1 | S | **Done 2026-10-24** (delete with 8 s Undo (no dialog)). Deletes immediately after one confirm |
| 10 | Scroll to new dictation and new blocks in the editor | UX | P1 | S | **Done 2026-10-24** (scroll to new lines, follow dictation). The editor's LazyColumn never scrolls to added items |
| 11 | TalkBack: read each checkbox together with its text | Accessibility | P1 | S | **Done 2026-10-24** (checkbox labels). `ui/editor/NoteEditorContent.kt`: checkboxes have no label |
| 12 | Room migration test (1→2) and DAO tests | Tests | P1 | M | **Done 2026-10-24** (Robolectric migration + DAO tests). There's no androidTest folder |
| 13 | Release signing, a single version source, an `assembleRelease` smoke test | Release | P1 | S | **Done 2026-10-24** (FLOATNOTE_* signing, version in gradle.properties, R8 build passes). No signingConfig, and the R8 rules file is empty |
| 14 | Ask for notification permission in the Float flow (known issue) | Permissions | P1 | S | **Done 2026-10-24** (asked once). POST_NOTIFICATIONS is declared but never requested |
| 15 | Load a note through the save mutex, inside a transaction | Data safety | P2 | S | Closing and quickly reopening a note can load a stale copy |
| 16 | Build the overlay on a window context | Android versions | P2 | S | Stale screen bounds on foldables, and a StrictMode violation |
| 17 | Drop `FLAG_WATCH_OUTSIDE_TOUCH` while it's a bubble | Battery | P3 | S | Every touch on the screen wakes the app |
| 18 | Device engine: one paragraph per recording, not one block per sentence | UX | P2 | S | `appendTranscript` adds a new block for each sentence |
| 19 | Backspace on an empty line merges it with the one above; undo/redo | UX | P2 | M | Today only the ✕ button removes a line |
| 20 | Note search and pinning | Product | P2 | M | The list is plain, sorted by last edit |
| 21 | Export and import notes (JSON or Markdown) | Data safety | P2 | M | Not implemented |
| 22 | Announce voice status to TalkBack (live region) | Accessibility | P2 | S | `voice/VoiceUi.kt` |
| 23 | Contrast: done tasks are at 50% alpha, and the hairline grip is about 1.2:1 | Accessibility | P2 | S | `ui/theme/Theme.kt` |
| 24 | Stop TalkBack reading glyphs like "◐" and "✕" (`clearAndSetSemantics`) | Accessibility | P2 | S | `ui/components/Buttons.kt` |
| 25 | Scale panel presets with the system font size | Accessibility | P2 | S | `overlay/WindowGeometry.kt` uses fixed dp sizes |
| 26 | Move voice error messages into strings.xml | Code quality | P2 | S | They're hard-coded English |
| 27 | Pure, tested state machine for the recognizer's restart, switch and watchdog logic | Tests | P2 | M | `voice/LiveRecognizer.kt` is untested |
| 28 | Tests for VoiceSettings and OverlayPrefs | Tests | P2 | S | None today |
| 29 | CI: unit tests, lint, release build | Release | P2 | S | There's no `.github/` folder |
| 30 | Play Store readiness: privacy policy, Data safety form, special-use foreground service justification | Release | P2 | S | Audio and notes are sent to a third-party API |
| 31 | Monochrome icon layer and a dark window theme | Polish | P3 | S | Lint also flags the missing monochrome icon |
| 32 | CLAUDE.md says settings use DataStore; they use SharedPreferences | Docs | P3 | S | **Done 2026-10-24** (fixed). Doc drift |
| 33 | Organise preview or diff, per-dictation "undo clean-up", and vocabulary learned from your corrections | Product | P3 | M | Builds on the new AI step and history |

All P0 and P1 items are done (needs a device check). Next up are the P2s: 15 (load/save race), 18 (one paragraph per dictation), 22–25 (accessibility), 27 (recognizer tests), 29 (CI).
