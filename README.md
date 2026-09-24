# StageScope

A wrist-mounted spectrum analyzer and level meter for theatre sound designers, built for Wear OS. Analyzes ambient sound picked up by the watch's own microphone — it does not control a mixer, record audio, or connect to anything.

## What's here

Three pages, swipeable left/right (LEVEL → SPECTRUM → RING), each with a small mode label at top
that doubles as the link to that page's Details/Actions screen (tap "LEVEL ›" etc.):

- **Level** — live unweighted RMS level (dBFS) as the hero reading, with PK/MAX/AVG chips and a
  restrained level bar below. Start/Stop is the one button on the main page; Reset, session info,
  Calibration, and Demo mode live in Level's Details screen.
- **Spectrum** — a large log-frequency plot fills the page: green solid trace for live data, peak
  hold, a dominant-frequency readout, and a touch/crown-controlled cursor. Freeze/Resume is the
  main action (Freeze shows the held trace dashed red with a "HELD" badge); Save, snapshot
  compare, and peak-hold reset live in Spectrum's Details screen.
- **Find a Ring** — the hero is the currently selected captured frequency with an explicit state
  (SEARCHING / DETECTING / LIVE / HELD / PINNED / PAUSED). A confirmed capture's frequency is
  frozen and held for a configurable 10/20/30s after the tone stops, and can be pinned to keep it
  selected indefinitely. Pin/Unpin and Clear are the two main-page actions; Start/Stop, the
  auto-hold duration, and the bounded capture history live in Ring's Details/Captures screens.
  This is a conservative heuristic based on spectral contrast and persistence over time — it does
  not identify feedback, suggest EQ, or distinguish a ring from a sustained musical tone.

Demo mode (a deterministic synthetic signal through the same DSP pipeline, no microphone use) and
manual reference-SPL calibration are both reached from Level's Details screen.

See `docs/screenshots/` for what the current build looks like on a real watch.

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

- The first time you press **Start** on any page, StageScope asks for microphone permission. Deny it and the page shows a distinct "permission denied" state rather than fake data.
- Capture is shared across all three pages: starting it on Level also feeds Spectrum and Ring, and swiping between pages never restarts it, resets Level's meters, or drops Ring's captured frequencies. Spectrum's Freeze only pauses *that page's* display, not the underlying capture.
- **Demo mode** (Level's Details screen) runs the exact same analysis code on a synthetic signal — useful for trying the UI without a live mic, or on an emulator. Every demo screen and saved demo snapshot is clearly labeled; demo and real snapshots are never mixed into the same comparison silently.
- **Calibration** (Level's Details screen → Calibrate SPL) lets you align the Level page's RMS reading to a trusted SPL meter reading, once, for the current input configuration. It relabels the reading "Estimated SPL" — it is not a certified measurement, and it's cleared automatically if the microphone configuration changes. See docs/MEASUREMENTS.md for exactly what this can and can't correct for.
- Measurement sessions keep the screen on for up to 2 minutes at a time (visible countdown, in Level's Details), then stop automatically; press Start again to continue. Backgrounding the app or turning the screen off also stops capture — you'll see "Paused" and need to press Start again.

## Project layout

See CLAUDE.md for the package structure and architecture notes, and docs/MEASUREMENTS.md for the DSP assumptions, calibration limitations, and a manual test checklist to run on a real watch.
