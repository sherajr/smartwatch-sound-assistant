# Phone companion screenshots

Captured on an **Android 16 (API 36) phone emulator**, not a real phone, with the **fixture data** the watch screenshots use (the example show "Our Town", `example.com` / `theatre.example` addresses). No API key, Google account or real person's data appears anywhere. Nothing here was sent or created: Gmail and Calendar are not connected, which is exactly what these screens show.

| File | What it shows |
| --- | --- |
| `01-home.png` | Status at a glance: items waiting for you, whether a watch is reachable (it isn't — the emulator isn't paired), the active provider and whether its key is set, Gmail/Calendar state |
| `02-chats.png` | The conversation list, naming the provider and model that actually answered |
| `03-email-draft.png` | An email draft in full — From, To, Subject, the whole message, the warning — with *Connect Gmail to send*, Edit, *Open in email app* and Cancel. There is no Confirm until Gmail is connected |
| `04-calendar-draft.png` | A calendar event with its time zone and UTC offset, the invitees, and the note that Google will email them when you confirm |
| `05-keep-handoff-and-uncertain-email.png` | A Keep item as **"Ready on phone"** (never "added"), and an email whose outcome is **unknown** — it asks you to check Gmail rather than guessing or re-sending |
| `06-show.png` | Production, performances, report options (people and groups are further down) |
| `07-issues.png` | The issue log shared with the watch |
| `08-settings.png`, `09-providers.png`, `10-usage.png` | Settings; the provider screen (masked key field, *Check key*, *Get a key*, model prices with the date they were checked); and your own limits, described as limits on what the app sends — not a spending cap at the provider |

Reproduce: `scripts\install-phone.ps1`, then open `stagescope://task/action/<id>` style links with `adb shell am start -a android.intent.action.VIEW -d <link> com.peaceantz.stagescope`.
