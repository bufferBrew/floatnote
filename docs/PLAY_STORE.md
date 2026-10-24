# Play Store submission notes

Answers to have ready when publishing. See [PRIVACY.md](../PRIVACY.md) for the policy itself
(host it, e.g. as a GitHub Pages page, and link it in the listing).

## Data safety form

| Question | Answer |
|---|---|
| Does the app collect or share user data? | **Yes, shares**: audio and text go to the API provider the user configures. Nothing goes to the developer. |
| Data types | **Audio** (voice recordings) when the Whisper engine is on. **Other user-generated content** (note and dictation text) when AI clean-up or Organise is used. |
| Is it processed ephemerally? | By FloatNote, yes: it keeps nothing it sends. The provider's retention is set by its own policy. |
| Is the data required? | No. Device speech recognition, and no AI features, works with no data leaving the app. |
| Encrypted in transit? | Yes: https only, enforced in code (`VoiceSettings.baseUrlValid`). |
| Can users request deletion? | Everything local is deleted with the app or with Delete/Clear history. There is no developer-side data. |

## Foreground service (special use)

`OverlayService` declares `foregroundServiceType="specialUse|microphone"` with the subtype
"Floating note window drawn over other apps while the user writes in it".
Justification for the review form:

> FloatNote shows a note window over other apps (SYSTEM_ALERT_WINDOW) so users can take notes
> without leaving the app they're using. The foreground service keeps that window, and any voice
> recording the user started, alive while another app is in front. It runs only while the
> floating note is open; the notification has a Close action, and closing the window stops it.

## Sensitive permissions

- `SYSTEM_ALERT_WINDOW`: core feature (the floating note). Requested through system Settings.
- `RECORD_AUDIO`: voice notes, only when the user taps Record (auto-record is opt-in).

## Before release

- Sign with the `FLOATNOTE_*` keystore properties (see CLAUDE.md), bump `VERSION_CODE`.
- Test a release (R8) build on a device: overlay, voice (both engines), Organise, export/import.
