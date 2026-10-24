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
- Status: open

## 2026-10-24 — Overlay not yet tested on a device
- What changed / what shortcut was taken: the overlay (focus hand-off, drag, resize, opacity, edge-panel entry) is built and unit-tested where it's pure logic, but hasn't been run on a phone or emulator. None is set up on the dev Mac.
- Ceiling (when this breaks): any device-specific behaviour, especially Samsung One UI and the Edge panel.
- Upgrade trigger (what to do when it's hit): install the debug APK on the phone, or set up an emulator, and go through the overlay by hand.
- Status: open
