# AI assistant — setup guide

Everything you do once to get the optional assistant working, in order. The watch's analyzer, ring finder, calibration and
snapshots keep working with **none of this** — no phone, account, key or network is ever required for them.

> The assistant is a **personal, bring-your-own-key** tool. There is no StageScope server and no shared developer key:
> you create your own API keys and Google project, they stay on your phone, and the bills go to your own accounts.

Quick map:

1. [What you need](#1-what-you-need)
2. [Build and install both apps](#2-build-and-install-both-apps)
3. [Check the watch ↔ phone link](#3-check-the-watch--phone-link)
4. [AI provider keys](#4-ai-provider-keys) (OpenAI · Gemini · Grok · Claude)
5. [Gmail and Calendar](#5-gmail-and-calendar-optional) (optional)
6. [Voice](#6-voice)
7. [Keep, "Continue on phone", notifications](#7-keep-continue-on-phone-and-notifications)
8. [Set up your show](#8-set-up-your-show)
9. [Troubleshooting](#9-troubleshooting)
10. [What is stored where / how to delete it](#10-what-is-stored-where--how-to-delete-it)
11. [Manual verification checklist](#11-manual-verification-checklist-for-you)

---

## 1. What you need

* A Wear OS watch (this project is built for a **Pixel Watch 5**) paired to an Android phone (a **Pixel 10**), both on a current Android/Wear OS version. The phone app needs Android 13 (API 33) or newer; the watch app runs on Wear OS 3 (API 30) or newer, but on-device speech recognition needs API 31+ (in practice Wear OS 4 or newer) — an older watch uses the record-and-let-the-phone-transcribe route.
* The Wear OS / Pixel Watch app on the phone, with the watch paired and connected (the Wear Data Layer carries the messages — it is the standard Wear connection, not Bluetooth you set up yourself).
* For **AI answers**: an API key from at least one of OpenAI, Google (Gemini), xAI (Grok) or Anthropic (Claude). *A ChatGPT / Claude / Gemini app subscription is not an API key* — API usage is a separate, pay-as-you-go account with each vendor.
* For **sending email / adding calendar events** (optional): a Google account and a small one-time Google Cloud setup (§5). Without it, every draft can still be opened in your own email or Calendar app with the text filled in.

Costs: each provider bills your API account per use. StageScope shows an **estimate** (from a price table it dates) and the provider's own figure when the API reports one; your invoice is the authority. You can set local limits in the phone app (Settings → Usage & limits) — these control what *this app* sends, they are **not** a spending cap at the provider.

## 2. Build and install both apps

From the repo root in PowerShell (see the README for the toolchain; `scripts\setup-doctor.ps1` checks it):

```powershell
scripts\build.ps1                 # builds BOTH debug APKs
scripts\check-signing.ps1         # confirms they are signed with the same key  (must say [OK])
scripts\install-phone.ps1         # installs the PHONE app on the one connected phone
scripts\install-launch.ps1        # installs the WATCH app on the one connected watch
```

The two apps are **different APKs for different devices that deliberately share one package name** (`com.peaceantz.stagescope`). The install scripts check the kind of device first and refuse to put the watch APK on a phone or the phone APK on a watch. If more than one device of a kind is connected, pass `-Serial <id>` (`adb devices -l`).

### Signing (important)

The Wear Data Layer only connects a watch app and a phone app that have the same package name **and the same signing certificate**. Both are built here with this machine's debug key, so they match — `scripts\check-signing.ps1` proves it and prints the fingerprints. Today's debug certificate:

* **SHA-1:** `0B:05:6D:E5:92:4C:52:10:95:5B:F8:A0:FC:BA:A9:B2:72:82:9F:66`

If an older copy of the watch app (from a different machine or key) is already installed, `adb install -r` fails with a signature error. The scripts then **stop and explain; they never uninstall anything for you** (that would delete its data). Either build with the key that signed the installed app, or uninstall it yourself if you're sure.

> Any other signing setup (a release key, another computer) changes this fingerprint. Rebuild **both** apps with the same key, and update the Google Cloud Android client (§5) with the new SHA-1.

Wireless debugging for a watch: `scripts\pair-wireless.ps1` (see the README).

## 3. Check the watch ↔ phone link

1. Open **StageScope on the phone → Home**. The *Watch* card should say **Connected: <your watch>**.
   *"No watch running StageScope is reachable"* means one of: the watch isn't connected in the Wear OS app, the watch app isn't installed, or the signatures differ (re-run `check-signing.ps1`).
2. On the watch, swipe **ANALYZER → RING → ASSISTANT**. Open the page; the status line under the microphone should read *Tap to ask* once a provider is ready, or tell you exactly what's missing (*"OpenAI needs a key on your phone"*, *"Phone not reachable"*…).
3. In the phone's **Settings → Developer → "Use the built-in test assistant"** you can try the whole watch ↔ phone round trip with a fake provider that costs nothing and can never send email or create events.

## 4. AI provider keys

On the phone: **Settings → AI providers**. For each provider: paste the key (the field is masked; it is never saved into a screen-rotation bundle), **Save key**, then **Check key** — that makes one free, authenticated list-models call and also records which models the key can use. Pick the model and (optionally) *Thorough* and *Web search*. **Use <provider>** makes it the active one for the watch and phone. You can remove a key at any time.

| Provider | Create a key | Notes |
|---|---|---|
| **ChatGPT / OpenAI** | <https://platform.openai.com/api-keys> | API billing is separate from a ChatGPT subscription. Default model `gpt-6.1-sol`; `gpt-6-astra` (most capable, ~5× the price) and `gpt-6-luna` (cheapest) are also listed. |
| **Google Gemini** | <https://aistudio.google.com/app/apikey> | Uses the Gemini **Interactions** API. Default `gemini-3.8-flash`. The free tier has tight limits; production use needs billing enabled on the Google project. |
| **xAI Grok** | <https://console.x.ai/> | Default `grok-4.7`. xAI reports the exact cost of each response, which StageScope prefers over its own estimate. |
| **Anthropic Claude** | <https://platform.claude.com/settings/keys> | API billing is separate from a Claude subscription. Default `claude-opus-5-5`; `claude-sonnet-5-5`, `claude-fable-5-1` and `claude-haiku-4-5` are listed. |

Each model row shows its **actual id**, the per-million-token prices and the date they were checked (**2026-10-01**) — prices and model ids change, so treat them as a guide. If your key can't use the selected model, the app says so and asks you to choose another; it **never swaps models or providers behind your back**. The answer on the watch and phone always names the provider and model that produced it.

Security notes: keys are encrypted with a non-exportable Android Keystore key and stored in a no-backup folder; they are never sent to the watch, never shown to an AI model, and never written to logs. If Android ever invalidates the Keystore key (for example after restoring a phone), the card says *"Saved key can't be read on this phone any more — enter it again."*

## 5. Gmail and Calendar (optional)

StageScope asks Google for the **narrowest** permissions, each separately and only when you tap *Connect*:

| Feature | Scope | What it allows |
|---|---|---|
| Send email | `https://www.googleapis.com/auth/gmail.send` | Send a message as you. It **cannot read** your mail. |
| Create events | `https://www.googleapis.com/auth/calendar.events.owned` | Create/change events on calendars you own. |
| Choose a calendar *(optional)* | `https://www.googleapis.com/auth/calendar.calendarlist.readonly` | List your calendars so you can pick one. |
| Identity | `openid`, `email` | Only so each draft can show **which account** will send/create, and so a confirmation can be bound to it. |

Nothing is sent or created until you review the *full* draft and tap **Confirm**. StageScope stores no Google token (Google's sign-in keeps and refreshes them).

### One-time Google Cloud setup

Google only lets an app use these permissions if its package name and signing certificate are registered in a Google Cloud project **you own**. (Based on Google's documentation as of 2026-10-01: *Authorize access to Google user data on Android* / `AuthorizationClient`.)

(Menu names below follow Google's current console; if one has moved, look for the equivalent page.)

1. Go to <https://console.cloud.google.com/> and create (or pick) a project.
2. **APIs & Services → Library:** enable the **Gmail API** and the **Google Calendar API**.
3. **Google Auth Platform → Branding / Audience:** fill in the app name and your email. Choose **External** and keep publishing status **Testing**; under **Audience → Test users** add **your own Google account(s)**. (A personal app used by you does not need Google's verification; it is limited to the test users you list.)
4. **Data Access → Add or remove scopes:** add the scopes in the table above (Gmail send; Calendar events owned; optionally the calendar list).
5. **Clients → Create client → Android:**
   * **Package name:** `com.peaceantz.stagescope`
   * **SHA-1 certificate fingerprint:** the value printed by `scripts\check-signing.ps1` (today: `0B:05:6D:E5:92:4C:52:10:95:5B:F8:A0:FC:BA:A9:B2:72:82:9F:66`)
6. On the phone: **Settings → Gmail & Calendar → Connect**. Choose your account, approve, and the card shows *Connected as you@…*.

Things to know:

* While the consent screen is in **Testing**, Google may expire your grant periodically (commonly every 7 days for sensitive scopes); StageScope then says *"Google access was revoked or expired — reconnect"* and sends nothing. This is Google's policy, not StageScope's; check Google's current rules.
* The SHA-1 must match **the key that signed the installed phone app** (§2). A different build key means *Connect* fails until the client is updated.
* *Revoke* at any time from the app (Disconnect) or <https://myaccount.google.com/permissions>.
* If Google asks for a **different account than before**, StageScope clears the other connected feature rather than show "connected" for an account the next confirmation wouldn't use.
* **Not connected?** Every email/event draft has an **Open in email app / Open in Calendar app** button that opens your own composer with the text filled in; the draft then shows *Ready on phone* until you mark it done.
* **Invitees:** an event with invitees makes Google email them an invitation when you confirm — the review card says so.

## 6. Voice

* **Asking.** On the watch Assistant page tap the big microphone (or a task card, or *Ask AI about this* in Analyzer/Ring Details). Allow the microphone the first time. It listens to **one bounded utterance** (10/20/30 s, your choice), then shows **what it heard** for you to check; only **▶ Send** sends it. **●** records again.
* **Recognition runs on the watch, on-device.** If the watch has no on-device recognizer it records a short clip and your **phone** turns it into text (on-device on the phone too). The clip is deleted once transcribed.
* **Optional cloud transcription** (phone → **Settings → Voice & speech**): off by default. If you turn it on, a recording is uploaded to OpenAI with your own OpenAI key *only when on-device recognition fails*; the cost (~$0.0045 per audio minute, checked 2026-10-01) is added to your usage.
* **Measurement pauses while you talk** and resumes afterwards only if it was running, the app is on screen, the microphone permission is still granted and the 2-minute keep-awake countdown hasn't ended. Pressing Stop while paused cancels the resume. Freeze, pinned rings and session data are untouched.
* **Speaking.** Theatre mode (the default) is silent. Replies are spoken **only** when you tap **Speak** — with a confirm step if no headphones are connected. The phone's *Speak* buttons work the same way, and the watch pauses measurement while the phone talks.
* **Offline memos.** With no phone nearby, a recording waits on the watch (Voice memos) and is sent when the phone returns. A question you ask while the phone is out of range waits up to 3 minutes before it is treated as stale (you then choose *Send anyway* or discard).

## 7. Keep, "Continue on phone", and notifications

* **Google Keep has no supported way for an app to add an item to an existing list.** StageScope therefore prepares the text and calls the result **Ready on phone** — never "added". On the phone you can **Share to Keep** (creates a new note), **Copy text**, or **Copy Gemini command** (*Add "spare mic tape" to my "Theatre Supplies" list in Keep* — a command you can say or paste to Gemini, which has its own Keep connector). Then tap **I did it — mark done**; that is recorded as *marked done by you (not verified by Keep)*.
* **Continue on phone** saves the exact draft or conversation on the phone and (a) asks Wear OS to open it, and (b) posts a notification whose link carries only an opaque stored id. The watch says **Opened on phone** only after the phone confirms the item is on screen; otherwise it tells you what happened (notification sent / saved but notifications are off / not found / no answer). Allow notifications on the phone (Home → *Allow notifications*) for the alert.

## 8. Set up your show

Phone → **Show**:

1. **Add production** (name, venue, time zone — an IANA id like `America/New_York`; blank uses the phone's zone — never silently UTC).
2. **Add performance** for each night (date, time, number/label, status) and **Select** the current one. Each performance has its own issues and its own report.
3. **People you can email:** add contacts and tick *I've checked this address*. A spoken name resolves to an address **only** for a verified contact (or an address you typed/said yourself); StageScope never guesses an address from a first name.
4. **Groups** (e.g. *Production team*) and the production's **Report recipients**.
5. **Report** sections and writing preferences (tone, greeting, signature). Reports use **only the selected performance's logged facts and your own dictated observations**.
6. **Calendar defaults:** default length and what to do when you say an hour with no AM/PM (*Ask me* is the default). Any default StageScope applies is shown on the review card.

The watch's **performance chip** can follow the phone's selection or pin a different performance for questions and issues logged on the watch.

## 9. Troubleshooting

| You see | Likely cause → fix |
|---|---|
| Watch: *Phone not reachable* | Watch not connected in the Wear OS app; phone app not installed; **signatures differ** (`scripts\check-signing.ps1`). Bluetooth/Wi-Fi off. |
| Phone Home: *No watch running StageScope is reachable* | Same as above; also confirm the watch app is the same build/version (Settings → Developer on the phone shows versions). |
| Question sits at *Waiting for your phone* | Phone asleep/away. Fresh questions send automatically on reconnect; after 3 minutes you're asked first. |
| *Your phone didn't confirm it got this* | The watch gave up after repeated sends. *Retry* re-sends the **same** question (the phone recognises it and never runs it twice). |
| *<Provider> needs a key on your phone* | Phone → Settings → AI providers → add and check a key. |
| *Your key was rejected* / *no access to this model* | Wrong or revoked key; or the model isn't available to that key/billing tier — pick another model (never auto-switched). |
| *Rate limited* / *quota* | Provider-side limit or billing; wait, or check the provider's console. |
| *Model unavailable* | The vendor doesn't offer that id to your key. Choose another in the provider card. |
| *Daily limit … reached* | Your own limit in Settings → Usage & limits. |
| Gmail/Calendar *Connect* fails | Package name or **SHA-1** in the Google Cloud Android client doesn't match the installed app; you're not listed as a **test user**; APIs not enabled; consent expired. |
| *Outcome unknown — check Gmail* | The send's result couldn't be confirmed (timeout/5xx after sending). It was **not** retried. Check your Gmail *Sent* folder, then tell StageScope what you found. |
| Calendar event at the wrong hour | Check the review card's time, UTC offset and time zone; say the zone/AM-PM explicitly; change the production's time zone. |
| Analyzer says *PAUSED · listening/speaking/phone* | Expected while the assistant uses the microphone/speaker; it resumes by itself (unless you pressed Stop or left the app). |

**Logs:** `scripts\logs.ps1 -Role watch` and `scripts\logs.ps1 -Role phone`. The app logs only the *kind* of an unexpected error (an exception class name) — never keys, tokens, headers, message text or measurements.

## 10. What is stored where / how to delete it

* **Phone** (`files/stagescope/`): conversations, drafts/actions, the request log, shows, the issue log, settings, a usage ledger and recent measurement snapshots (≤ 24). **Keys**: Keystore-encrypted, `noBackupFilesDir`. Nothing is in cloud backup or device transfer.
* **Watch** (`files/assistant/`): the question outbox, a cache of the last answers/providers/shows, preferences, voice memos (audio only until transcribed) and the watch's copy of the issue log. Nothing is in cloud backup or device transfer.
* **Leaves your devices only when you act:** your question text and (if attached) the measurement snapshot go to the **selected** provider; optionally a recording to OpenAI if you enabled cloud transcription; Gmail/Calendar calls after you confirm. There is no continuous upload.
* **Delete:** remove a key in the provider card; Disconnect Google in Settings → Gmail & Calendar; clear all app data in Android's app settings on either device (this erases that device's conversations, issues and keys).

## 11. Manual verification checklist (for you)

I could not run any of this without your accounts and devices. Please walk through it once; each item says what *good* looks like.

**Link & install**
- [ ] `scripts\check-signing.ps1` → `[OK]`; watch + phone installed; phone Home shows *Connected*.
- [ ] Watch: the pager is ANALYZER → RING → ASSISTANT; Analyzer and Ring behave as before (crown rotates the instrument; Ring tiles pin on tap).

**Providers** (repeat per provider you use)
- [ ] Save + *Check key* shows *Key works*; a deliberately wrong key shows *rejected* and is not marked valid.
- [ ] A watch question gets an answer that names the provider and model; the web-search toggle off ⇒ no web claims.
- [ ] Cancel during *Thinking…* stops it; the reply says anything already done is not undone.

**Measurement context**
- [ ] Start Analyzer, ask *"what do you see?"* — the answer refers to the readings **before** you spoke; with Freeze on it says the spectrum was held.
- [ ] While you talk, Analyzer shows **PAUSED · listening** and returns to live afterwards; press **Stop** during the question and it must **not** restart.
- [ ] With Ring pins from an earlier session and nothing sounding, ask about rings: the answer must call them saved/held/historical, **not** "currently ringing".
- [ ] Calibrate, ask again: levels are reported as raw dBFS plus a separately labelled estimated SPL.

**Speech**
- [ ] On-device recognition works on the watch (or the recording → phone route is offered). You always see the transcript before sending.
- [ ] Speak: with no headphones it asks first; measurement pauses while it talks and resumes after. Phone *Speak* pauses the watch too.

**Actions** (use throw-away recipients/events!)
- [ ] An email draft shows From / To / Subject / full body; **Confirm** sends exactly once (check *Sent*); tapping Confirm on the watch **and** the phone sends once.
- [ ] Editing a draft after review voids the confirmation (it asks again).
- [ ] A calendar event shows date, time, UTC offset and zone; a DST-edge time shows an assumption. Delete test events afterward.
- [ ] Keep: *Ready on phone* → Share to Keep → mark done reads *Marked done by you*.
- [ ] Airplane mode on the phone, then Confirm: nothing is sent, the state says what happened; reconnecting never re-sends an email.

**Offline & sync**
- [ ] Phone away: a question queues; return within 3 min ⇒ sent; after 3 min ⇒ *Old question — not sent* with *Send it anyway*.
- [ ] Log an issue on the watch with the phone off; it appears on the phone after reconnect; edit the same issue differently on both while apart ⇒ both versions appear as a conflict to choose from.

**Round-screen layout** (see MEASUREMENTS.md): the Assistant page, Listen, Reply and Action screens on your watch — no clipped text at the round edge, no more than two round buttons in any lower row.
