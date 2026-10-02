# Assistant screenshots (watch)

These were captured on a **Wear OS 6 emulator** (API 36, round, 480×480 at 320 dpi — the 240 dp reference size), **not on a real watch**, so they show layout and wording but not a physical bezel, brightness, or touch feel.

They show **fixture data only**: the example show "Our Town", `example.com` / `theatre.example` addresses, a made-up provider status and model names. No API key, Google account, or real person's data appears anywhere. The emulator has no paired phone, so the screens that depend on one show the honest "phone isn't reachable" states — for instance `06-confirm-phone-unreachable.png` is what Confirm does when the phone can't be reached: nothing is sent, and it says so.

| File | What it shows |
| --- | --- |
| `01-assistant-home.png`, `02-assistant-home-scrolled.png` | The third page: mic button, the question in progress, provider and show chips, the latest answer, what needs you, task shortcuts |
| `03-needs-you.png` | Drafts and decisions waiting |
| `04-email-review.png`, `05-email-review-message.png` | An email draft: the warning comes first, then From / To / Subject / Message in full; two round controls (cancel, confirm) |
| `06-confirm-phone-unreachable.png` | The answer to Confirm with no phone, scrolled into view |
| `07-answer.png`, `08-answer-detail.png` | A structured answer, one paragraph per list item |
| `09-listening.png`, `10-nothing-heard.png` | Listening (fallback: record, the phone transcribes), and the nothing-heard result |
| `11-voice-memos.png`, `12-check-transcript.png` | Offline voice memos; checking a transcript before anything is sent |
| `13-issue-log.png`, `14-issue-detail.png` | The watch's own issue log (works with no phone); deleting is a separate chip that needs a second tap |
| `15-provider-picker.png`, `16-setup.png` | Provider choice (keys live on the phone only) and setup status |
| `17-speak-confirm.png` | Theatre mode asks before anything is spoken aloud with no headphones connected |
| `18-analyzer-still-works.png` | The original Analyzer, unchanged |

Reproduce: `scripts\install-launch.ps1`, write fixture JSON into `files/assistant/` with `run-as`, and open the page with `am start --es com.peaceantz.stagescope.extra.SHORTCUT ask_ai` (see CLAUDE.md → "Wear OS emulator").
