# StageScope

A wrist-mounted spectrum analyzer and level meter for theatre sound designers, built for Wear OS. Analyzes ambient sound picked up by the watch's own microphone — it does not control a mixer, record audio, or connect to anything.

## What's here

Two pages, swipeable left/right (ANALYZER → RING), each with a small mode label at top that
doubles as the link to that page's Details/Actions screen (tap "ANALYZER ›" / "RING ›"):

- **Analyzer** — a single circular instrument combining Level and Spectrum. The center shows a
  large, steady RMS level (dBFS, or "Estimated SPL" once calibrated) with PK/MAX/AVG in Details; a
  radial spectrum fills the surrounding ring (log-frequency angle, fixed dBFS-scale radius, a
  deliberate gap at the bottom for the two action controls). Tap the ring or turn the crown to move
  the selection cursor — the frequency/dB readout below the center number always refers to that
  exact selected band, never a different one. Start/Stop is the primary action; Freeze/Resume holds
  the spectrum only (the center level and Ring detection keep running — a "SPECTRUM HELD" tag makes
  that explicit) — peak hold, saved snapshots, and snapshot comparison live in Details.
- **Ring** — a five-slot bank of confirmed persistent tones, each independently pinnable. The hero
  shows the currently *selected* capture with an explicit state (SEARCHING / DETECTING / LIVE /
  HELD / HISTORICAL / PINNED / PAUSED); "Captures N/5 ›" opens the full bank list to select, pin, or
  unpin any of them individually — pinning one never unpins another. This is a conservative
  heuristic based on spectral contrast and persistence over time — it does not identify feedback,
  suggest EQ, or distinguish a ring from a sustained musical tone.

Demo mode (a deterministic synthetic signal through the same DSP pipeline, no microphone use) and
manual reference-SPL calibration are both reached from Analyzer's Details screen.

See `docs/screenshots/` for what the current build looks like on a real watch.

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
scripts\build.ps1   # -> app\build\outputs\apk\debug\app-debug.apk
scripts\test.ps1    # JVM unit tests for the DSP layer
scripts\lint.ps1    # Android Lint
```

Or from VS Code: **Terminal → Run Task →** "StageScope: Build Debug APK" / "Run Unit Tests" / "Lint".

## Installing on your watch

### Over USB
Plug the watch in (with USB debugging enabled on it), then:
```powershell
scripts\install-launch.ps1
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

If more than one device is attached, both scripts will list serials and ask you to pass `-Serial <id>`.

### Logs
```powershell
scripts\logs.ps1
```
Filtered to StageScope's own process plus crash traces.

## Using it

- The first time you press **Start** on Analyzer or Ring, StageScope asks for microphone permission. Deny it and the page shows a distinct "permission denied" state rather than fake data.
- Capture is shared across both pages: starting it on Analyzer also feeds Ring, and swiping between pages never restarts it, resets Analyzer's meters, or drops Ring's captured frequencies. Analyzer's Freeze only pauses *the radial spectrum*, not the underlying capture — the center level and Ring detection keep running.
- **Demo mode** (Analyzer's Details screen) runs the exact same analysis code on a synthetic signal — useful for trying the UI without a live mic, or on an emulator. Every demo screen and saved demo snapshot is clearly labeled; demo and real snapshots/captures are never mixed into the same comparison or saved surface silently.
- **Calibration** (Analyzer's Details screen → Calibrate SPL) is a guided flow that aligns Analyzer's RMS reading to a trusted SPL meter reading, once, for the current input configuration. It relabels the reading "Estimated SPL" — it is not a certified measurement, and it's cleared automatically if the microphone configuration changes. See docs/MEASUREMENTS.md for exactly what this can and can't correct for.
- Measurement sessions keep the screen on for up to 2 minutes at a time (visible countdown, in Analyzer's Details), then stop automatically; press Start again to continue. Backgrounding the app or turning the screen off also stops capture — you'll see "Paused" and need to press Start again.
- **Ring's five-slot bank**: up to five distinct confirmed frequencies at once, each independently pinnable (`Pin`/`Unpin` per capture — pinning one never touches another). When full, a new tone only takes a slot if an existing one has expired (unpinned, past its auto-hold window) or is clearly weaker than the new candidate; if all five are pinned, the Ring page says so plainly. "Clear unpinned" and "Clear all" (behind a confirm step) are both available from the Captures list.
- **The watch-face complication** always shows the *most prominent currently-confirmed* ring — chosen purely by spectral strength with light hysteresis, never by "pinned, else most recent." Selecting or pinning a capture in-app never changes what the complication shows. It reads cached, historical data only and never opens the microphone, labeled "Last analyzed" rather than implying live listening.

## Project layout

See CLAUDE.md for the package structure and architecture notes, and docs/MEASUREMENTS.md for the DSP assumptions, calibration limitations, and a manual test checklist to run on a real watch.
