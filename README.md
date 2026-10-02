# StageScope

A wrist-mounted spectrum analyzer, level meter and ring finder for theatre sound designers, built for Wear OS. Analyzes ambient sound picked up by the watch's own microphone — it does not control a mixer, and **the instruments never record audio, use the network, or need an account**.

There is also an **optional AI assistant** (a third watch page plus a phone companion app) that you can ask about what the instruments show, use to log show issues, and have draft emails and calendar events *for you to confirm*. It is bring-your-own-key, entirely opt-in, and the watch is fully usable without it. See [AI assistant](#ai-assistant-optional) below.

## What's here

Three pages, swipeable left/right (ANALYZER → RING → ASSISTANT). The two instrument pages have a small mode label at top that
doubles as the link to that page's Details/Actions screen (tap "ANALYZER ›" / "RING ›"):

- **Analyzer** — a single circular instrument combining Level and Spectrum. The center shows a
  large, steady RMS level (dBFS, or "Estimated SPL" once calibrated) with PK/MAX/AVG in Details; a
  radial spectrum fills the surrounding ring (log-frequency angle, fixed dBFS-scale radius, a
  deliberate gap at the bottom for the two action controls). Tap the ring or turn the crown to move
  the selection cursor — the frequency/dB readout below the center number always refers to that
  exact selected band, never a different one. Start/Stop is the primary action; Freeze/Resume holds
  the spectrum only (the center level and Ring detection keep running — a "SPECTRUM HELD" tag makes
  that explicit) — peak hold, saved snapshots, and snapshot comparison live in Details.
- **Ring** — a five-slot bank of confirmed persistent tones, shown as five tiles at once (LIVE /
  HELD / SAVE), each independently pinnable — tap a populated tile to pin it (its fill switches to
  the theme's held accent) or tap again to unpin it; pinning one never unpins another. "Clear
  unpinned" sits next to Start/Stop on the Ring page itself for an immediate, no-confirmation sweep
  of everything unpinned; Ring Details has "Clear all", which also removes pinned captures, behind a
  confirm step. This is a conservative heuristic based on spectral contrast and persistence over
  time — it does not identify feedback, suggest EQ, or distinguish a ring from a sustained musical
  tone.

- **Assistant** *(optional, needs the phone app)* — a scrolling page (the crown scrolls it): a big microphone button, the active
  provider and model, the show/performance, task cards (analyze this sound, log an issue, email the report, add to Keep,
  calendar event) and what needs your attention. See below.

Demo mode (a deterministic synthetic signal through the same DSP pipeline, no microphone use) and
manual reference-SPL calibration are both reached from Analyzer's Details screen. Analyzer's and Ring's Details also have **"Ask AI about this"**.

See `docs/screenshots/` for the instruments on a real watch, and `docs/screenshots/assistant/` (watch) and `docs/screenshots/phone/` for the assistant — those were captured on emulators with fixture data, not on real devices.

## AI assistant (optional)

Two apps share one package name and signing key: the **watch app** (this project's original app, plus the Assistant page) and a new **phone companion**. The phone holds your API keys, talks to the AI providers and Google, and does the long work; the watch asks and shows.

**What it can do**

- Answer questions about the sound — grounded in a **measurement snapshot taken the instant you ask** (raw dBFS and calibrated *Estimated SPL* kept separate; rings you pinned earlier are described as history, not as "ringing now"). Answers are structured *observed / plausible explanations / next check / how to re-measure*, and never claim to know it's feedback or tell you to cut a frequency on a console.
- Log show issues by dictating or typing on the watch — **logging works with no phone, network or AI** (dictation itself uses the watch's own speech service, which may need a connection) — synced watch ⇄ phone without losing edits made on either.
- Draft a **performance report** or **issue email** from the selected performance's logged facts, and a **calendar event** — as *drafts you review in full and confirm*. Nothing is sent or created until you confirm; a confirmation is tied to the exact text, recipients and Google account you saw.
- Prepare a **Keep item** — honestly as *"Ready on phone"* (Google gives apps no way to add to an existing Keep list), never as "added".
- **Continue on phone**: open the exact saved draft or conversation on the phone.

**Providers** — your own account with any of **OpenAI (ChatGPT API)**, **Google Gemini**, **xAI Grok** or **Anthropic Claude**; the model in use is always shown and is never silently swapped.

**Quiet by default** — theatre mode (silent) is on; replies are text, spoken only when you tap Speak; haptics are off. Asking opens the watch's own dictation screen (StageScope never records you or sends audio to the phone); you check the words before anything is sent. Measurement is paused while that screen is open and resumed after (only if it was running, the app is on screen, permission is still granted and the 2-minute keep-awake countdown allows it). A Tile, complication or notification can open the Assistant page but can never start a dictation.

**Costs and limits** — each provider bills *your* API account. The phone shows estimated and (where the API reports it) actual cost and lets you set local request/budget limits; these limit what the app sends, they are not a provider billing cap.

Setup (keys, Google Cloud, signing, voice) → **[docs/AI_SETUP.md](docs/AI_SETUP.md)**. How it works, guarantees and what's tested → **[docs/AI_ARCHITECTURE.md](docs/AI_ARCHITECTURE.md)**.

> The AI features are implemented, covered by unit tests, and have been driven screen by screen on a round Wear OS emulator and a phone emulator — but they have **not been exercised against live providers, Google, or a real watch/phone pair** (no keys or accounts were available where they were built). See the manual checklist at the end of AI_SETUP.md.

## Appearance

Analyzer Details → Appearance offers five color themes (Phosphor Green, Ice Cyan, Warm Amber,
Violet, Night Red) plus a separate Dim toggle that works with any of them. Every screen, the radial
spectrum, and the Tile all read the same palette — changing themes visibly changes the actual
instrumentation, not just button chrome. The choice persists (`AppSettings.theme`) and existing
installs without a saved theme default to Phosphor Green.

## Calibration

A guided four-step flow (Prepare → Enter reference reading → Measure → Review/Save), reached from
Analyzer Details → "Calibrate SPL": explains what calibration does and offers "Use dBFS" to skip it
entirely; the reference-reading step requires an explicit touch or crown adjustment before
continuing (an untouched example value is never silently saved as a real reading); Measure runs an
explicit ~3-second sampling window that energy-averages the whole window into one dB conversion
(never averaging per-block dB values), with live signal-quality feedback and rejection of
clipped/too-variable/stale/Demo-mode/config-changed samples; Review shows the reference reading,
the measured raw watch level, and the resulting offset before an explicit Save, ending in an
unmistakable "Calibration saved" success screen.

## Prerequisites

Nothing to install by hand for local builds — `scripts\setup-doctor.ps1` checks what's present, and the JDK/Android SDK/Gradle used to build this project were installed into `%LOCALAPPDATA%\Android\` (not this repo) the first time it was set up. If you're on a fresh machine, run:

```powershell
scripts\setup-doctor.ps1
```

It reports what's missing. (This project intentionally keeps the SDK/JDK out of the repo and out of `PATH`/global settings — `local.properties`, which points Gradle at the SDK, is gitignored and machine-specific.)

## Build, test, lint

```powershell
scripts\build.ps1                    # both APKs  (-Target watch | phone | all)
                                     #   watch -> app\build\outputs\apk\debug\app-debug.apk
                                     #   phone -> phone\build\outputs\apk\debug\phone-debug.apk
scripts\test.ps1                     # JVM unit tests: shared + phone + watch, plus the install scripts' device selection  (-Module all|shared|phone|watch|scripts)
scripts\lint.ps1                     # Android Lint for both apps              (-Module all|watch|phone)
scripts\check-signing.ps1            # both APKs must share one signing key (needed for watch ⇄ phone)
```

Or from VS Code: **Terminal → Run Task →** "StageScope: Build Debug APK" / "Build Phone APK" / "Build Both APKs" / "Run Unit Tests" / "Lint (watch + phone)" / "Check Signing".

The repo has three Gradle modules: `:app` (the watch app), `:phone` (the companion) and `:shared` (pure Kotlin used by both).

## Installing

The watch app and the phone app are **different APKs that share one package name**, so each install script checks what kind of device it is talking to and refuses the wrong one (it also never uninstalls anything to work around a signing mismatch).

### The phone app
Enable USB debugging on the phone, plug it in, then:
```powershell
scripts\install-phone.ps1            # add -Serial <id> if more than one phone is attached
```

### The watch app — over USB
Plug the watch in (with USB debugging enabled on it), then:
```powershell
scripts\install-launch.ps1           # add -Serial <id> if more than one watch is attached
```

### Over Wi-Fi (typical for a watch)
1. On the watch: **Settings → Developer options → Wireless debugging**. Turn it on, then tap "Pair new device" to get an IP, a pairing port, and a 6-digit code. Note the separate "connection" port shown on the main Wireless debugging screen too.
2. From your PC:
   ```powershell
   scripts\pair-wireless.ps1 -PairIp 192.168.1.23 -PairPort 41235 -PairCode 123456 -ConnectPort 41234
   ```
3. Then:
   ```powershell
   scripts\install-launch.ps1
   ```

If more than one device of the right kind is attached, the scripts list them and ask you to pass `-Serial <id>`. `adb` often lists *one* watch twice over Wi-Fi — once as `192.168.1.x:port` and once as an `adb-<serial>-xxxxxx._adb-tls-connect._tcp` name — and the scripts recognise that as a single watch (they go by its hardware serial) and use the `ip:port` entry; `scripts\setup-doctor.ps1` shows the second line as "same device as …".

### Logs
```powershell
scripts\logs.ps1 -Role watch        # or -Role phone, or -Serial <id>
```
Filtered to StageScope's own process plus crash traces. The app logs only the *kind* of an unexpected error — never keys, tokens, message text or measurements.

## Using it

- The first time you press **Start** on Analyzer or Ring, StageScope asks for microphone permission. Deny it and the page shows a distinct "permission denied" state rather than fake data.
- Capture is shared across both pages: starting it on Analyzer also feeds Ring, and swiping between pages never restarts it, resets Analyzer's meters, or drops Ring's captured frequencies. Analyzer's Freeze only pauses *the radial spectrum*, not the underlying capture — the center level and Ring detection keep running.
- **Demo mode** (Analyzer's Details screen) runs the exact same analysis code on a synthetic signal — useful for trying the UI without a live mic, or on an emulator. Every demo screen and saved demo snapshot is clearly labeled; demo and real snapshots/captures are never mixed into the same comparison or saved surface silently.
- **Calibration** (Analyzer's Details screen → Calibrate SPL) is a guided flow that aligns Analyzer's RMS reading to a trusted SPL meter reading, once, for the current input configuration. It relabels the reading "Estimated SPL" — it is not a certified measurement, and it's cleared automatically if the microphone configuration changes. See docs/MEASUREMENTS.md for exactly what this can and can't correct for.
- Measurement sessions keep the screen on for up to 2 minutes at a time (visible countdown, in Analyzer's Details), then stop automatically; press Start again to continue. The app has no foreground service, so Android may stop delivering real microphone audio once the app is no longer on screen (readings then show "Silence — check mic"); StageScope does not currently stop the session itself when you leave the app — the countdown does. (Earlier versions of this README said backgrounding "stops capture" and shows "Paused"; the code never did that.)
- When you ask the assistant a question, measurement is **paused** for that moment (Analyzer shows "PAUSED · listening" and the last reading) and resumes afterwards — see "AI assistant" above for the exact conditions.
- **Ring's five-slot bank**: up to five distinct confirmed frequencies at once, each independently pinnable — tap a populated tile on the Ring page to pin/unpin it directly (pinning one never touches another). When full, a new tone only takes a slot if an existing one has expired (unpinned, past its auto-hold window) or is clearly weaker than the new candidate; if all five are pinned, the Ring page says so plainly. "Clear unpinned" is right on the Ring page next to Start/Stop, works immediately with no confirmation, and only ever removes unpinned captures; "Clear all" (which also removes pinned captures, behind a confirm step) lives in Ring Details.
- **The watch-face complication** always shows the *most prominent currently-confirmed* ring — chosen purely by spectral strength with light hysteresis, never by "pinned, else most recent." Selecting or pinning a capture in-app never changes what the complication shows. It reads cached, historical data only and never opens the microphone, labeled "Last analyzed" rather than implying live listening.

## Project layout

See CLAUDE.md for the package structure and architecture notes, docs/MEASUREMENTS.md for the DSP assumptions, calibration limitations, and a manual test checklist to run on a real watch, docs/AI_SETUP.md to set up the assistant, and docs/AI_ARCHITECTURE.md for how it works and what is (and isn't) verified.
