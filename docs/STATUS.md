# Status

## Done (this pass — polished redesign: combined Analyzer, themes, guided calibration, 5-ring bank, prominence-based complication)

A full redesign per the updated brief: combined Level+Spectrum into one circular ANALYZER page,
added 5 selectable color themes + Dim, replaced the calibration controls with a guided 4-step flow,
reworked Ring into an independently-pinnable 5-slot capture bank, and changed the watch-face
complication to show the most prominent currently-confirmed ring instead of "pinned, else most
recent." Preserved the existing audio pipeline, `CaptureSession` sharing, DSP invariants, and watch
shortcut/installation workflow throughout — see `git log`/prior revisions of this file for the two
earlier passes (visual redesign + Ring rework; app logo + Tile/complication) this one builds on.

### 1. Combined circular Analyzer (`ui/analyzer/`)
- New `ModePage` order: ANALYZER → RING (was LEVEL → SPECTRUM → RING). `AnalyzerViewModel` merges
  what were separate `LevelViewModel`/`SpectrumViewModel` onto the one shared `CaptureSession` —
  same throttled ~10Hz publish, same keep-awake countdown, same calibration-offset handling, same
  Freeze-is-local (now spectrum-only, not page-swap-only) contract.
- `RadialMapping.kt` — pure, zero-Compose-dependency geometry (unit-tested, `RadialMappingTest`):
  logarithmic angle mapping over a 270° arc with a deliberate 90° gap at the bottom (where the two
  action buttons sit), a fixed -90..0 dBFS radial scale (never autoscaled), and band aggregation
  (2049 raw FFT bins → 56 display bands via **max**, not average, so narrow peaks survive; each band
  records the real bin that produced its value, so a selected band's Hz and dB always match — the
  old Spectrum page's readout mixed a dominant-frequency Hz with a differently-sourced cursor dB,
  deliberately not repeated here).
- `RadialSpectrumCanvas.kt` draws the annulus: sparse dB grid rings + frequency ticks, live bars,
  a decaying peak-hold outline, a dashed comparison-snapshot outline, and a selection-cursor spoke —
  four visually distinguishable elements. Tap the ring or turn the crown to move the cursor (auto-
  follows the loudest band until the user interacts); `Modifier.pointerInput` computes the tap angle
  and `RadialMapping.fractionForAngle` returns null for a tap inside the gap.
- `AnalyzerDetailsScreen` carries PK/MAX/AVG, sample-rate/FFT/resolution info, Save/Snapshots, Clear
  peak hold, Calibrate SPL, Demo toggle, Appearance, Watch shortcuts — moved out of the main dial
  after on-device testing showed the center "safe zone" is too tight to hold that plus the primary
  reading and frequency readout at once (see the on-device findings below).

### 2. Five color themes + Dim (`ui/theme/Theme.kt`, `ui/settings/AppearanceScreen.kt`)
- `StageScopePalette` (a data class of the same field names the old `StageScopeColors` object had)
  + `StageScopePalettes` (Phosphor Green / Ice Cyan / Warm Amber / Violet / Night Red, each with a
  contrasting live/held accent pair) + `LocalStageScopePalette` (CompositionLocal, provided by
  `StageScopeTheme(theme, dimAppearance)`). Audited and moved every direct `StageScopeColors.X`
  reference in a Composable context (`LevelBar`→removed as dead code once PK/MAX/AVG left the main
  dial, `ActionControls`, `RingScreen`, `RingCapturesScreen`, `CalibrationScreen`, the new
  `RadialSpectrumCanvas`) onto `LocalStageScopePalette.current`. The Tile builds its ProtoLayout
  `ColorScheme` from the same palette + the persisted Dim flag (`stageScopeTileColorScheme`), so the
  Tile visibly follows the in-app theme, not just the app's own buttons.
- `AppSettings.theme: AppTheme` — a new field with a default (`PHOSPHOR_GREEN`), so an existing
  install's `settings.json` (missing the key) decodes unchanged (`AppSettingsTest` verifies this
  against a literal pre-this-pass JSON string, plus that unknown future keys don't break decoding).

### 3. Guided calibration (`ui/settings/CalibrationViewModel.kt` + `CalibrationScreen.kt`)
- Replaced the single free-form "dial in a target, Confirm" screen with 4 steps: **Prepare**
  (explains calibration, "Use dBFS" to skip, an expandable "what this can't fix" note) → **Enter
  reference reading** (crown/±, requires an explicit touch or an explicit "use shown value"
  confirmation — an untouched example can't be silently saved as a real reading) → **Measure** (an
  explicit "Measure reference" action, ~3s sampling with a live progress bar and clip/live-dB
  feedback) → **Review/Save** (reference reading, measured raw level, resulting offset, Retry/Save)
  → an explicit **success** screen ("Calibration saved. Level readings now show Estimated SPL.")
  with Done back to Analyzer.
- `dsp/CalibrationSampleAccumulator.kt` — pulled the energy-averaging math (the thing the old
  ViewModel did inline, from a single latest block, per the brief's own critique) into a pure,
  unit-tested class: accumulates energy across the whole ~3s window and converts to dB exactly once
  (`CalibrationSampleAccumulatorTest` checks this against the same -9.03 dBFS invariant
  `LevelMeterTest` uses, and that a loud block isn't washed out by a quiet one the way naive
  dB-averaging would be), plus per-block dB spread (stability) and any-block clipping.
- Save is rejected — with a stated reason, not just a disabled button — if the sample clipped, was
  too variable (>6 dB per-block spread), is older than 90s, was taken in Demo mode, or the mic
  configuration changed since measuring; re-checked at Save time, not just at measurement completion.

### 4. Five-slot independently-pinnable Ring bank (`dsp/RingTracker.kt`, `ui/ring/`)
- `RingTracker.pin(id)`/`unpin(id)`/`setPinned(id, Boolean)` replace the old exclusive
  `pin(id)` (unpinned everything else)/`unpin()` (no-arg, cleared all pins) — any 0–5 of the 5 slots
  can now be pinned independently. `clearUnpinned()` is new alongside the existing `clearSelected()`/
  `clearAll()`.
- `RingTrackerSettings.maxCaptures` (was `maxHistorySize`) defaults to 5, with deterministic,
  tested capacity rules: fill empty slots first; never evict pinned; prefer an expired-unpinned slot;
  otherwise only evict the weakest unpinned slot if the new tone is `replacementMarginDb` (6dB)
  stronger (avoids churn); if all five are pinned the tone stays a live track but doesn't claim a
  slot, and `RingSnapshot.allSlotsPinned` lets the UI say so plainly.
- `confirm()`'s recurring-tone dedup tolerance is now resolution-aware
  (`dedupToleranceBins × the frame's actual binWidthHz`, an explicit new settings field) instead of
  the previous `matchToleranceBins × 4.0` — a hardcoded "4 Hz per bin" assumption the brief flagged.
- New **most-prominent-ring selector** (`RingSnapshot.mostProminentCaptureId`): the strongest
  currently-LIVE capture by EMA-smoothed spectral prominence, computed entirely independently of
  `selectCapture`/pin state. Switches promptly to a clearly stronger candidate
  (`prominenceSwitchMarginDb`, 4dB) but requires a near-equal candidate to lead for
  `prominenceDwellMs` (600ms) first — hysteresis against flapping. Sticky when nothing is currently
  live (returns the last id it held, so a caller can show "cached" info), cleared when that specific
  capture is removed.
- Pinned captures (id/frequency/save-time only, never audio) persist across restarts via the new
  `data/RingBankRepository.kt` (`ring_bank.json`, same plain-JSON pattern as the other repositories)
  and are replayed via `RingTracker.restoreCapture()` at `RingViewModel` startup — marked
  `restoredFromDisk` (shown "SAVED" in the Captures list) until a live detection updates them again,
  seeded so their ids never collide with a freshly-confirmed capture.
- `RingScreen` gained a persistent "Captures N/5 ›" entry point (was only shown when ≥1 "other
  candidate" existed); `RingCapturesScreen` now lists all 5 slots with per-row Select/Pin-Unpin,
  "SAVED"/state labels, "Clear unpinned", and "Clear all" behind an explicit confirm step (armed by
  one tap, executed by a second, cancellable) rather than a single-tap destructive action.
- Added `RingCaptureState.EXPIRED` ("HISTORICAL" in the UI), separate from `HELD`, so an
  auto-hold-expired-but-retained capture reads distinctly from one still within its hold window.

### 5. Complication shows the most prominent ring, not "pinned, else last" (`widget/`, `RingViewModel`)
- `RingViewModel.resolveComplicationCapture()` reads `RingSnapshot.mostProminentCaptureId` (falling
  back to the strongest remaining historical capture if that id was since cleared) instead of the
  old `pinned ?: mostRecentlySeen`. The diff key that gates writes to `SurfaceSummaryRepository`
  (`captureId, pinned, frequencyHz`) is recomputed from this resolver on every published snapshot,
  so a change driven purely by the audio stream — no Pin tap at all — still reaches the complication.
- Complication/Tile wording changed to never claim liveness ("Last analyzed <time>", title "LAST"
  always, a pinned note only as secondary detail) since they only ever read cached, disk-backed data
  and must never imply the watch face is actively listening.
- `ShortcutRequest.forShortcut` is now a pure function (`ui/nav/ShortcutIntents.kt`, no
  `android.content.Intent` dependency) so the LEVEL/SPECTRUM→ANALYZER, MEASURE→ANALYZER+start, and
  RING(+captureId) routing is directly unit-tested (`ShortcutRequestTest`) without Robolectric.
  Existing Tile/complication `PendingIntent`s using the old `level`/`spectrum` shortcut strings keep
  working unmodified, now landing on the combined Analyzer page.

### On-device findings (Pixel Watch 5, API 37) — real bugs caught and fixed this pass
- **Layout overlap in the Analyzer dial**: the first on-device render showed the primary RMS number,
  unit label, and frequency readout genuinely overlapping the radial ring and each other, and the
  selection-cursor spoke crossed straight through the center text. Root cause: the round-safe content
  area between the title and button rows is noticeably shorter than it is wide, so a circular gauge
  sized to that box's *height* leaves much less central "safe zone" than expected, especially once
  PK/MAX/AVG chips were stacked in there too. Fixed by moving PK/MAX/AVG to Details, shrinking the
  cursor-readout font (new `compactReadoutStyle()`), tightening the annulus inner/outer radii, and
  capping the cursor spoke's inner reach at the bars' own `innerRadius` instead of reaching further
  in. Re-verified on-device after the fix — clean, no overlap.
- **A real navigation bug, not a test artifact**: a *second* Tile/complication-style shortcut tap
  (`EXTRA_SHORTCUT` via `onNewIntent`) while the app was already open on a different page silently
  failed to switch pages — confirmed via targeted logging that `onNewIntent` fired correctly and the
  outer `StageScopeNavHost` recomposed with the new `pendingAction`, but the pager never scrolled.
  Root cause: Wear Navigation composes `composable(ROUTE_MAIN) { ... }`'s content once per backstack
  entry and doesn't re-invoke that closure just because the *enclosing* `StageScopeNavHost` function
  recomposes with new parameter values — a closure over the raw `pendingAction` parameter kept
  seeing the value from the very first composition. Fixed with `rememberUpdatedState(pendingAction)`
  (and the same for `onPendingActionConsumed`), giving the inner `LaunchedEffect` a stable reference
  that always reads the latest value. A fresh cold launch (the `onCreate` path) was never affected —
  only a second tap while already running. Re-verified on-device after the fix, including with the
  synthetic ring tone from Demo mode: Ring correctly showed LIVE/223Hz/prominence/2-of-5 captures
  after navigating there mid-measurement.
- **Synthetic touch input does not work on this device**: `adb shell input tap`/`swipe` never reach
  the touchscreen driver (`getevent` during a synthetic tap shows zero events from `raydium_ts`,
  confirmed both before and after trying `input touchscreen`, `monkey`, and held/long taps) — this is
  a device/driver limitation, matching the *previous* session's own unresolved tap-through caveat for
  the Tile, now confirmed to be a general adb-on-this-unit issue rather than something specific to
  Tiles. Screens reachable only via a button tap (Calibration, Appearance, Ring Captures list,
  Analyzer/Ring Details) could not be exercised interactively this pass; screens reachable via the
  `EXTRA_SHORTCUT` intent contract, or by writing `files/settings.json` via `adb shell run-as` (to
  flip Demo mode) plus `pm grant` for the mic permission, could — and were, including live and
  demo-mode Analyzer and Ring rendering.

### Checks run this session
- `gradlew testDebugUnitTest` → BUILD SUCCESSFUL, **89/89 passed**: the 51 pre-existing DSP/data/
  widget tests (`RingTrackerTest` rewritten for the new pin/unpin/eviction/most-prominent API, still
  covering every prior behavior plus the new one) plus new suites —
  `RadialMappingTest` (9, pure geometry/aggregation), `CalibrationSampleAccumulatorTest` (5, energy
  averaging + stability/clip signals), `AppSettingsTest` (3, theme backward-compat/round-trip/
  forward-compat), `ShortcutRequestTest` (6, shortcut→page routing incl. legacy LEVEL/SPECTRUM→
  Analyzer), plus 17 new `RingTrackerTest` cases for the five-slot bank, independent pins, eviction
  policy, resolution-aware dedup, most-prominent hysteresis/stickiness, and restore-from-disk.
- `gradlew lintDebug` → BUILD SUCCESSFUL, **0 errors, 12 warnings** — the same 12 pre-existing ones
  from the prior pass (Gradle/dependency freshness ×6, `allowBackup` deprecation, `ObsoleteSdkInt` on
  `mipmap-anydpi-v26`, `IconDuplicatesConfig`, `WearRecents`, `NewerVersionAvailable`); no new
  warnings introduced.
- `gradlew assembleDebug` → BUILD SUCCESSFUL. APK: `app\build\outputs\apk\debug\app-debug.apk`
  (~54.7 MB debug/unminified).
- Installed and launched on the same **Pixel Watch 5** (API 37) used in prior sessions; no crash, no
  `FATAL EXCEPTION`/`AndroidRuntime` in logcat across the whole session. Rendered and inspected, via
  screenshots pulled off-device (see `docs/screenshots/analyzer.png`, `analyzer-demo.png`,
  `ring-searching.png`, `ring-live.png`): Analyzer's Ready/live/Demo states including the radial
  spectrum with real ambient audio and with Demo's synthetic tones; Ring's SEARCHING state and its
  LIVE state mid-Demo-cycle with 2 of 5 slots confirmed. The two bugs above were found and fixed
  through this same process.

## Next concrete action (physical steps only you can do)
1. **Confirm real touch interaction** on the actual hardware — this session's automated checks could
   not exercise Calibration, Appearance, the Ring Captures list, or either Details screen, since
   synthetic touch input does not reach this specific watch via adb (see "on-device findings" above).
   Walk the full Calibration flow (all 4 steps + success), try all 5 themes with Dim on/off, and open
   the 5-slot Captures list with a couple of real captures pinned.
2. **Real (non-demo) five-ring test**: with several distinct steady tones (or a tuner app playing
   different notes) near the mic, confirm up to 5 are captured and held simultaneously, that pinning
   several independently works exactly as described, and that a 6th tone's replacement behavior
   (expired-unpinned first, else sufficiently-stronger-unpinned, else nothing) matches expectations
   in a real acoustic environment, not just the synthesized unit tests.
3. **Watch-face complication, live**: get two real rings of different strength going (or louder/
   quieter tone sources), confirm the complication switches to the stronger one on its own without
   any Pin tap, confirm it does not defer to an older pinned-but-now-quieter ring, and confirm the
   dwell/hysteresis feels right (not flappy, not sluggish) rather than just matching the unit tests'
   synthetic timing.
4. **Tile visual check with each theme** — swipe to the Tile carousel and confirm its colors actually
   follow the in-app Appearance selection, not just the app's own screens.
5. Everything from the manual checklist in `docs/MEASUREMENTS.md` not already covered by the
   automated on-device checks above is still outstanding for a full physical pass, particularly the
   192dp/227dp/240dp round-screen and enlarged-font-size checks — only the one connected Pixel Watch
   5 was available this session, no emulator was set up (deliberately, to avoid adding a new tool to
   the pinned toolchain), so the smaller/larger round-screen sizes were not visually verified.

## Prior passes — unchanged this session except where called out above
See git history / prior revisions of this file for: the original visual redesign + Ring rework pass
(black/live-green/held-red system, `HorizontalPager`, `CaptureSession` sharing, `RingTracker`'s
original track→capture pipeline); and the app-logo + Tile/complication pass (adaptive icon,
`StageScopeTileService`/`StageScopeComplicationService`, `SurfaceSummaryRepository`,
`ui/nav/ShortcutIntents` contract, `WatchShortcutsHelpScreen`). `RingTracker`'s original behavior
contract is unchanged by this pass except where explicitly listed above (five-slot capacity,
independent pins, resolution-aware dedup, most-prominent selector, restore-from-disk).
