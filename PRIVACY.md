# FloatNote privacy policy

_Last updated: 24 October 2026_

FloatNote is a note-taking app. It has no accounts, no ads, no analytics and no servers of its own.

## What stays on your phone

- **Notes, dictation history and your dictionary** are stored only on your phone, in the app's private database.
- **Your API key** (if you add one) is encrypted with a key that never leaves your phone's secure hardware (Android Keystore). It's excluded from Android backups.
- **Exports** go only to the file you choose, wherever you choose to save it.

## What leaves your phone, and only when you turn it on

FloatNote sends data only to the speech or AI service *you* configure in Settings → API key and server. OpenAI is the default, and any OpenAI-compatible service works (e.g. Groq). Your agreement with that provider governs what it does with the data.

| Feature | Sent | When |
|---|---|---|
| Whisper speech engine | The audio you record | Each time you stop a recording |
| Clean up dictation (AI) | The text of each dictation | Only if you turn it on |
| ✦ Organise (AI) | The text of the note | Only when you tap Organise |

With the **This phone** speech engine, recognition is done by your phone's own speech service (e.g. Google's), under that service's terms. FloatNote doesn't send the audio anywhere itself.

Your API key is sent only to the server address you set, only over https, and never to a different host without your confirmation.

## Permissions

- **Microphone:** to record voice notes, only while recording is on (the waveform shows it).
- **Display over other apps:** to show the floating note.
- **Notifications:** for the floating note's Show/Close notification.
- **Internet:** only for the services above.

## Contact

Questions: open an issue at https://github.com/bufferBrew/floatnote/issues.
