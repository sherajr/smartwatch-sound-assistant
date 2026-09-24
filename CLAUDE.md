# StageScope

Wear OS spectrum analyzer / level meter for theatre sound design. Kotlin + Jetpack Compose (Wear Material3, classic AGP DSL — not AGP 9's built-in-Kotlin mode).

## Commands (PowerShell, from repo root)
- `scripts\setup-doctor.ps1` — check JDK/SDK/adb, list connected devices
- `scripts\build.ps1` — assembleDebug → `app\build\outputs\apk\debug\app-debug.apk`
- `scripts\test.ps1` — JVM unit tests (DSP + ring-tracker correctness)
- `scripts\lint.ps1` — Android Lint
- `scripts\install-launch.ps1 [-Serial <id>]` — install + launch on a connected watch
- `scripts\logs.ps1 [-Serial <id>]` — filtered logcat
- `scripts\pair-wireless.ps1 -PairIp -PairPort -PairCode -ConnectPort` — Wi-Fi debugging pairing
- Or VS Code: Terminal → Run Task → "StageScope: ..."

## Toolchain (pinned; verify-then-pin, not latest-always)
JDK 17 (Temurin) · AGP 8.13.0 · Gradle 8.13 · Kotlin 2.4.20 · compileSdk/targetSdk 36 · minSdk 30
Compose BOM 2026.03.01 · Wear Compose (material3/foundation/navigation) 1.6.2
Tiles 1.6.2 + ProtoLayout 1.4.2 + ProtoLayout Material3 1.4.2 (Tile surface) · watchface-complications-data-source-ktx 1.3.0 (complication) · guava-android 33.7.1 (pulled in solely so `ListenableFuture` resolves — Tiles' own transitive `listenablefuture` stub gets neutralized by Guava's published Gradle metadata otherwise; see comment in `app/build.gradle.kts`).
Installed under `%LOCALAPPDATA%\Android\{jdk-17,Sdk,gradle-9.5.0}`, not in this repo (see `local.properties`, gitignored).

## Architecture (`app/src/main/kotlin/com/peaceantz/stagescope/`)
- `dsp/` — pure Kotlin, zero Android deps: `DbScale`, `LevelMeter`, `FastFourierTransform`, `SpectrumAnalyzer`, `RingTracker` (+ `RingCapture`/`RingCaptureState`/`RingTrackerSettings`), `MonotonicClock`, `FrequencyBands`. Fully unit-tested (`app/src/test/`).
- `audio/` — `AudioCaptureEngine` (real mic), `CaptureSession` (the ONE shared session all three pages register on — see below), `CaptureConfig`/`CaptureStatus`, `PcmSource`.
- `demo/` — `DemoSignalGenerator`: deterministic synthetic PCM, implements `PcmSource`, never touches the mic.
- `data/` — `SettingsRepository`/`SnapshotRepository`/`SurfaceSummaryRepository`: plain JSON files in `filesDir`, no DB/DataStore. `SurfaceSummaryRepository` (`surface_summary.json`) is the small versioned cache the Tile/complication read — last RMS+unit+timestamp, saved ring id/frequency/timestamp/pinned — written only at meaningful events (Level stop; Ring capture/pin/unpin/clear), never per-frame, never for demo-mode data.
- `widget/` — the two watch-native surfaces, additive to the app, sharing `SurfaceSummaryRepository` and nothing else from the live session: `widget/tile/StageScopeTileService` (`androidx.wear.tiles.TileService`, layout built with `androidx.wear.protolayout.material3`) and `widget/complication/StageScopeComplicationService` (`SuspendingComplicationDataSourceService`, SHORT_TEXT + MONOCHROMATIC_IMAGE). `widget/WidgetFormatting.kt` has the shared timestamp/frequency/dB formatters (unit-tested); `widget/SurfaceUpdateNotifier.kt` pushes a refresh to both surfaces right after a repository write. Both services launch `MainActivity` via `ui/nav/ShortcutIntents.kt`'s `EXTRA_SHORTCUT` contract (a String extra, not an Intent action — a Tile's declarative `ActionBuilders.launchAction` can't set a custom action).
- `ui/main/CaptureSessionViewModel` — owns the shared `CaptureSession`, scoped to the `"main"` nav destination (see nav below).
- `ui/{level,spectrum,ring}/` — each page's ViewModel + main Composable + a `*DetailsScreen`. `ring/` also has `RingCapturesScreen` (bounded history).
- `ui/nav/StageScopeNavHost` — `AppScaffold` + `HorizontalPagerScaffold` + `HorizontalPager` (LEVEL→SPECTRUM→RING, rotary paging disabled) inside route `"main"`; Details/Calibration/Snapshots/Captures are separate pushed routes that re-fetch the same ViewModels via `viewModel(viewModelStoreOwner = navController.getBackStackEntry("main"))`.
- `ui/theme` — color tokens + type scale (`Metrics.kt`). `ui/components` — `ModePageScaffold` (shared page skeleton), `CompactGlyphButton`, `DetailButton`, `LevelBar`, `StateBadge`.
- `AppContainer` (`StageScopeApp.kt`) — manual constructor injection, no DI framework.

## Conventions
- No DI framework, no Room, no DataStore.
- **Capture is owned above the pager.** `CaptureSession` (one `PcmSource`, fan-out to N listeners) is created once per `"main"` visit and shared by all three page ViewModels via `viewModel(viewModelStoreOwner = <main's backstack entry>)`. Swiping pages never restarts capture, resets meters, or drops Ring captures — only explicit Stop (or backgrounding) does. Spectrum's Freeze is page-local (stops that page's analyzer from ingesting blocks) and does *not* touch the shared session.
- UI state publishes at ~10 Hz, throttled via `SystemClock.elapsedRealtime()` in each `onBlock` callback.
- Rotary/crown: `androidx.compose.ui.input.rotary.onRotaryScrollEvent` (core Compose UI, not wear-compose-foundation). The pager has `rotaryScrollableBehavior = null` so Spectrum's cursor and Ring's history can claim the crown.
- **Round-screen buttons: max 2 per page, in the lower action row.** A 3rd 48dp circular control at that row gets clipped by the bezel (confirmed on a 240dp round device — the outer two lose their edges). Details/Actions is reached via the tappable mode-title text (`"LEVEL ›"` etc.), not a 3rd button or a corner icon (a corner-pinned control clips even harder — confirmed).
- `ButtonDefaults.filledTonalButtonColors()` (→ `DetailButton`) for Details-screen controls, never the default `Button` (which fills with `colorScheme.primary` = live-green and reproduces the old oversized-colored-button look). Long button labels need `Modifier.fillMaxWidth()` or they overflow the round-safe width and clip.
- **Tile/complication taps** land on `MainActivity` as a `ShortcutRequest` (`ui/nav/ShortcutIntents.kt`), consumed exactly once by `StageScopeNavHost`'s pager-scoped `LaunchedEffect`. `MainActivity` only reads the launch `Intent` when `savedInstanceState == null` (Android's own signal for "not a recreation") — a rotation or a process-death respawn from Recents redelivers the same `Intent` without a new `onNewIntent` call and must not replay a stale Measure/navigate request; a real re-tap always arrives via `onNewIntent`, which is unconditionally honored. Don't move the intent read into a place that runs on every recomposition/recreation.

## Measurement invariants (full detail: docs/MEASUREMENTS.md)
- RMS dBFS = `10*log10(mean(x^2))`; sample peak dBFS = `20*log10(max(abs(x)))`. Floor: -90 dBFS.
- Session average accumulates energy (sum of squares / count), converts to dB once — never averages dB values.
- Spectrum: Hann window, 4096-sample FFT, 50% overlap, one-sided amplitude normalization.
- Calibration offset applies only to Level's RMS-derived readings; sample peak and the Spectrum plot always stay raw dBFS. Invalidates when `CaptureConfig.fingerprint()` changes.
- **Ring**: `RingTracker` separates instantaneous observations → transient tracks (persistent ID, EMA-smoothed freq, 350ms confirm dwell, 150ms dropout tolerance) → confirmed `RingCapture` records (frozen frequency, auto-held 10/20/30s, pinnable, bounded history of 8). All timing uses injected `MonotonicClock`, not frame counts — see `RingTrackerTest` for the full behavior contract (jitter, dedup, multi-tone, pin/clear, pause/config-change).

## Known first-draft simplifications
- Snapshot rename uses a fixed preset-name picker, not a system text keyboard (avoids the alpha-only `androidx.wear:wear-input`).
- No haptics anywhere (satisfies "default off" trivially; not wired up at all this pass).
- `SurfaceSummaryRepository`'s saved unit label ("dBFS" vs "Estimated SPL") is frozen at the moment it's written (Level stop), same precedent as `SpectrumSnapshot` freezing its own calibration fields — it is not retroactively rewritten if calibration changes afterward without a new measurement.
- App icon: adaptive icon (`res/mipmap-anydpi-v26/ic_launcher*.xml` + per-density `mipmap-*/ic_launcher_foreground.png`), generated from the source logo with a one-off Java/`ImageIO` program (no ImageMagick/PIL in this environment) rather than hand-authored — regenerate the same way if the logo changes, don't hand-edit the PNGs. The complication's `android:icon`/`MonochromaticImage` needs a strictly single-color white asset (`res/drawable-nodpi/ic_stagescope_mono.png`); the Tile's `android:icon`/`PREVIEW` metadata has no such restriction and uses the full-color mark instead (`res/drawable/` + `drawable-round/ic_stagescope_logo.png`, ≥384×384px — Lint's `TilePreviewImageFormat` enforces this). `mipmap-anydpi-v26` (not the unqualified `mipmap-anydpi` Lint's `ObsoleteSdkInt` suggests) is required — this pinned aapt2 doesn't resolve adaptive-icon XML in the unqualified folder.

README.md has setup/usage. docs/MEASUREMENTS.md has DSP detail + manual test checklist. docs/STATUS.md has current state.
