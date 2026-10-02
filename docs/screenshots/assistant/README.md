# Assistant screenshots (watch)

These were captured on a **Wear OS 6 emulator** (API 36, round, 480×480 at 320 dpi — the 240 dp reference size) **except the two marked "Real Pixel Watch 5"**, which are screenshots of the owner's watch (Android 17). They show layout and wording; a physical bezel, brightness and touch should still be checked by eye (see the manual checklist).

They show **fixture data only**: the example show "Our Town", `example.com` / `theatre.example` addresses, a made-up provider status and model names. No API key, Google account, or real person's data appears anywhere. The emulator has no paired phone, so the screens that depend on one show the honest "phone isn't reachable" states — for instance `06-confirm-phone-unreachable.png` is what Confirm does when the phone can't be reached: nothing is sent, and it says so. (The two real-watch images show only the system's own dictation screen and an ambient level reading.)

| File | What it shows |
| --- | --- |
| `01-assistant-home.png`, `02-assistant-home-scrolled.png` | The third page: mic button, the question in progress, provider and show chips, the latest answer, what needs you, task shortcuts |
| `03-needs-you.png` | Drafts and decisions waiting |
| `04-email-review.png`, `05-email-review-message.png` | An email draft: the warning comes first, then From / To / Subject / Message in full; two round controls (cancel, confirm) |
| `06-confirm-phone-unreachable.png` | The answer to Confirm with no phone, scrolled into view |
| `07-answer.png`, `08-answer-detail.png` | A structured answer, one paragraph per list item |
| `09-dictation-unavailable.png` | What you see when the watch has no dictation screen (the handler was disabled on the emulator to produce it): an honest message, *Type instead*, *Continue on phone*, and a single ✕ (there is nothing to retry) |
| `10-system-dictation-screen-real-watch.png` | **Real Pixel Watch 5** — the watch's own dictation screen (Gboard) that opens when you tap the mic. It is the system's UI, not StageScope's; it decides for itself when to listen and stop |
| `11-older-recordings.png` | Recordings an earlier version made: kept, never uploaded, deleted only by you (two taps) |
| `12-check-your-words.png` | Checking dictated words before anything is sent: the words, the retained-measurement note, their source, then *Type instead* / *Discard*, and two round controls (● dictate again, ▶ send) |
| `13-issue-log.png`, `14-issue-detail.png` | The watch's own issue log (works with no phone); deleting is a separate chip that needs a second tap |
| `15-provider-picker.png`, `16-setup.png` | Provider choice (keys live on the phone only) and setup status |
| `17-speak-confirm.png` | Theatre mode asks before anything is spoken aloud with no headphones connected |
| `18-analyzer-still-works.png` | The original Analyzer, unchanged |
| `19-analyzer-after-dictation-real-watch.png` | **Real Pixel Watch 5** — Analyzer after dictating and backing out with the spectrum frozen: still measuring (live level), still *SPECTRUM HELD* |

Reproduce: `scripts\install-launch.ps1`, write fixture JSON into `files/assistant/` with `run-as`, and open the page with `am start --es com.peaceantz.stagescope.extra.SHORTCUT ask_ai` (see CLAUDE.md → "Wear OS emulator").
