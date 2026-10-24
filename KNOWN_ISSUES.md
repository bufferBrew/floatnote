## 2026-10-24 — Saves rewrite every block
- What changed / what shortcut was taken: `NoteDao.replace` deletes and re-inserts all of a note's blocks on each (debounced) save, instead of diffing.
- Ceiling (when this breaks): notes with thousands of blocks, where a save takes long enough to drop frames or drain battery.
- Upgrade trigger (what to do when it's hit): diff old vs new block lists and upsert/delete only what changed.
- Status: open

## 2026-10-24 — Only one note, no list screen
- What changed / what shortcut was taken: `MainActivity` always opens the most recently edited note (or creates one). There is no way to browse, create or delete notes yet.
- Ceiling (when this breaks): as soon as a second note is needed.
- Upgrade trigger (what to do when it's hit): add a note list screen and pass a note id into `NoteViewModel`.
- Status: open

## 2026-10-24 — New task inside the done section in move-to-bottom mode
- What changed / what shortcut was taken: pressing Enter in (or adding after) a done task inserts an open task among the done ones. It stays there until the next toggle or mode switch reorders the group.
- Ceiling (when this breaks): a user editing a completed task and pressing Enter sees an open task below done ones.
- Upgrade trigger (what to do when it's hit): in `NoteEditor.insertAfter`, place new open tasks after the last open task of the group when the mode is MoveToBottom.
- Status: open
