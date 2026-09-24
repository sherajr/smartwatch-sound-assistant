# Status

## Follow-up fix — crown input, centered ring bank, smaller analyzer readout

- Corrected the rotary modifier order on both main screens: the event handler now precedes the
  focus target. A shared `rotaryOrientation` modifier also uses Wear's hierarchical focus support
  instead of a one-time focus request, so retained pages can reclaim input when they become active
  again. The existing orientation lock, rotation math, and debounced persistence are unchanged.
- Gave the Ring grid/status Column the full available width. Previously, without the optional
  all-pinned or demo status text, it shrank to the grid width and was placed at the Box's left edge.
- Reduced the Analyzer frequency/dBFS readout from 16sp to 12sp and set its line height to 14sp,
  removing the inherited large numeral line box that placed it too low in the circular center.
- Added Android Compose regression tests for actual crown-event delivery, switching between
  retained focus branches, returning from a secondary route, and locking/unlocking orientation.

Validation for this follow-up: `git diff --check` passes. The combined
`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` command was attempted, but could not
download Gradle 8.13 (`services.gradle.org`: network unreachable), before project compilation.
The new instrumentation tests are **not yet run**, and no watch/emulator is attached in this
environment. Prior successful checks below describe earlier commits, not this follow-up.

On a configured machine, run the existing build/test/lint scripts and
`gradlew.bat :app:connectedDebugAndroidTest`. Then verify on the watch:

1. Turn the real crown on Analyzer, swipe to Ring and turn it again, then open/close Details and
   repeat. Lock should block rotation; unlocking should resume without restarting the app.
2. Check that the five tiles share the watch's horizontal center with 0, 2, and 5 captures, with
   and without Demo/all-pinned status text.
3. Check the compact readout with both Hz and kHz values, live/held spectra and larger font settings,
   including rotated orientations. Confirm it stays clear of the spectrum.

## Done (this pass — full-bleed Analyzer, circumference level meter, all-5 Ring tiles, crown rotation)

A layout/interaction pass on top of the prior redesign, driven by on-device screenshots showing the
Analyzer dial was a small decorative circle inside a header/footer Column, and Ring's single "hero"
frequency hid the other four captures behind a tap. Four changes: the Analyzer is now a genuinely
full-bleed circular instrument with a separate circumference level meter; the Ring page shows all
five capture slots at once with direct per-tile pin control; the crown now rotates the whole
instrument (previously the spectrum cursor) so the display can be read from any wrist angle; and a
handful of real layout bugs this same on-device process caught (see "On-device findings" below).
Preserved throughout: the shared `CaptureSession`, all DSP invariants, calibration, themes, the
five-slot bank's capacity/eviction/most-prominent-selector rules, and the Tile/complication contract.

### 1. Full-bleed Analyzer (`ui/analyzer/AnalyzerScreen.kt`, `InstrumentGeometry.kt`, `LevelMeterRing.kt`, `LevelMeterScale.kt`)
- Replaced `ModePageScaffold`'s title-Column-content-Column-button-row structure (which measured the
  circular gauge against a box shorter than the true screen diameter) with a single full-screen
  `BoxWithConstraints`: the mode title, primary reading, and buttons are all positioned as overlays
  within it rather than reserving separate header/footer bands.
- `InstrumentGeometry.compute(usableRadius)` derives every boundary — level-meter outer/inner
  radius, spectrum outer/inner radius, and the center "safe zone" radius — from one shared
  `usableRadius = min(width, height) / 2`, so the Canvas layers and the center content's own sizing
  always agree (`InstrumentGeometryTest`). The spectrum annulus now sits just inside the level meter
  with a small visible gap, rather than occupying a small central circle.
- New `LevelMeterRing.kt`: a thin, nearly-complete circumference track (same bottom gap as the
  spectrum) with an illuminated arc for the current RMS level, on a **fixed, documented scale**
  (`LevelMeterScale`) that depends on calibration state — -90..0 dBFS uncalibrated, a fixed 30..120
  dB Estimated-SPL band once calibrated — so a positive SPL number is never plotted against a scale
  built for negative dBFS. Continues updating from live RMS even while Freeze holds the spectrum
  annulus still. Clipping swaps its color to the same semantic accent the center CLIP badge uses
  (supplementary, not the sole non-color signal). `LevelMeterScaleTest` covers both ranges and
  clamping past either endpoint.
- `RadialSpectrumCanvas.kt` dropped the frequency tick labels/lines around the outer edge (that
  information is available via the tap-to-select cursor readout) to declutter now that there's a
  second ring immediately outside it; the dB grid, live bars, peak hold, comparison overlay, and
  cursor spoke are otherwise unchanged.

### 2. All five Ring captures at once (`ui/ring/RingTilesGrid.kt`, `RingSlotAssigner.kt`)
- Replaced the single giant hero frequency + "Captures N/5 ›" link with a 2-1-2 grid of all five
  slots, each showing frequency, pin state, and a compact LIVE/HELD/SAVE status — visible without
  opening Captures.
- `RingSlotAssigner` gives each live capture a **stable** slot: unlike `RingSnapshot.history`
  (sorted by `lastSeenAtMs`, which changes every live frame), a capture keeps its slot for as long as
  it exists, and a new capture only claims an empty slot — so tiles never reshuffle out from under a
  tap as prominence/recency changes (`RingSlotAssignerTest`). The most-prominent capture gets an
  understated outline on whichever slot it currently occupies, never a duplicated hero number.
- Tap a populated tile to toggle its pin directly; long-press to select it and open Captures for
  inspection/clearing — no need to select-then-use-a-separate-button. An empty slot shows a
  restrained, bordered "—" (an earlier version used a solid black background, which was
  indistinguishable from the screen behind it — see on-device findings).
- `RingViewModel` gained `isActive` (derived from `CaptureStatus`, not from `RingUiState`'s shape) —
  `RingUiState` has no distinct "not started" case (it's always `Measuring`, even before Start is
  ever pressed), so the bottom Start/Stop control needed its own signal. Also now calls
  `publishCurrent()` once at startup after `restorePinnedBank()`, so pinned captures restored from a
  previous run appear in the grid immediately rather than only after the next status event.

### 3. Crown rotates the instrument, not the spectrum cursor (`ui/rotation/`)
- `OrientationViewModel` (scoped like `CaptureSession`, above the pager) holds one shared, unwrapped
  rotation angle; `Modifier.graphicsLayer { rotationZ = angleDegrees }` applied once per screen
  rotates text, spectrum, level meter, buttons, and Ring tiles together as a single rigid unit.
  Persisted (debounced, 800ms after the crown stops) via `AppSettings.instrumentOrientationDegrees`;
  `OrientationMath` keeps the live angle unwrapped so it never jumps at the 0/360 seam, normalizing
  only the persisted/displayed value (`OrientationMathTest`).
- Applied *inside* each page's own content — never around the pager or the nav host — so page-swipe
  and swipe-to-dismiss gestures stay in true screen space at any angle, and the pager's own page-
  indicator dots never rotate. Every secondary screen (`RotatedContent.kt`) visually inherits the
  angle without any crown rewiring; their own rotary behavior (list scroll, Calibration's number
  adjustment) is untouched since rotary events are scroll deltas, not screen positions.
  `AnalyzerDetailsScreen`/`RingDetailsScreen` both gained "Reset orientation" and "Lock orientation".
- Band selection on the spectrum is now touch-only (tap the arc) since the crown is fully committed
  to rotation; `AnalyzerViewModel.moveCursor` (crown-only) was deleted as dead code.

### On-device findings (Pixel Watch 5-class round display, 240dp/480px) — real bugs caught and fixed this pass
- **Ring's restored pins never appeared without an unrelated event first**: injecting a 5-entry
  pinned bank and cold-launching straight to Ring showed all 5 slots empty. `restorePinnedBank()`
  mutates the tracker directly but never re-published `_uiState` (whose hardcoded initial value
  predates the restore); nothing else calls `publishCurrent()` until Start/pin/stop happens. Fixed by
  publishing once at the end of `init {}`.
- **The Ring grid silently clipped its bottom row (and any status line below it)**: `RingTilesGrid`
  originally sized its own tiles from `maxWidth` alone; the actual constraint was vertical —
  `ModePageScaffold`'s title/button chrome left only ~110–120dp of a 240dp screen for content, not
  enough for three tile rows plus an "All 5 pinned" message. Fixed by computing tile size from a
  single `BoxWithConstraints` around the *whole* content (grid + any status lines) and tightening
  `ModePageScaffold`'s own padding (now Ring's only remaining consumer — Analyzer moved to its own
  full-bleed layout).
- **Stacked tile text got its outer characters clipped**: `RingTile` used `.clip(CircleShape)` with
  two centered text lines; a *circle* clip cuts a line at its own (narrower) chord once that line
  isn't at the exact vertical center, not just at the tile's bounding square — losing leading/
  trailing characters ("220 Hz" → "20 Hz"). Fixed by dropping the clip (background/border alone
  already paint/stroke the circle correctly) and switching the on-tile frequency to the shorter
  `formatFrequencyCompact` form ("1.5kHz" vs. "1.50 kHz") so it reliably fits one line at tile size.
- **A too-aggressive first chrome-tightening pass hid the "RING ›" title entirely** (cut by the round
  bezel / occluded by the system TimeText clock) — the top inset needed to stay close to the
  original value even while the bottom/title-internal padding shrank; only trimming evenly was wrong.
- **Long status text silently overflowed the round bezel at its vertical position, not just the
  rectangular width**: "All 5 pinned — unpin or clear one to capture another" and "DEMO — synthetic
  signal" both got clipped at one edge (a rectangle-fits check isn't enough on a round display,
  per the project's own layout convention) — fixed with `TextAlign.Center` + `fillMaxWidth()` and a
  taller reserved height budget for a message long enough to wrap to two lines.
- Confirmed empirically (not just by code inspection) that `Modifier.graphicsLayer { rotationZ }` is
  a pure post-layout render transform: 0°/90°/180° screenshots all show the full spectrum, meter,
  text, and buttons correctly rotated together; a couple of 45°/270° screenshots taken immediately
  after a cold app launch caught the state before the first FFT frame completed (text/buttons present,
  rings not yet drawn) — a screenshot-timing artifact, not an angle-dependent rendering bug, confirmed
  by a longer-settled retry at 180° showing everything present.

### Checks run this session
- `gradlew testDebugUnitTest` → BUILD SUCCESSFUL, all suites passing, including four new ones added
  this pass: `InstrumentGeometryTest`, `LevelMeterScaleTest`, `OrientationMathTest`,
  `RingSlotAssignerTest` (all pure/JVM, no Android/Compose dependency).
- `gradlew lintDebug` (forced fresh, not the cached report) → BUILD SUCCESSFUL, **0 errors, 12
  warnings**, all pre-existing (dependency-freshness notices, `allowBackup` deprecation,
  `ObsoleteSdkInt` on `mipmap-anydpi-v26`, `IconDuplicatesConfig`, `WearRecents`) — none introduced by
  this pass.
- `gradlew assembleDebug` → BUILD SUCCESSFUL. APK: `app\build\outputs\apk\debug\app-debug.apk`.
- Installed and iterated on a connected 480×480px/320dpi (240dp) round watch via `adb`. Since
  synthetic touch does not reach this device's touchscreen driver (pre-existing, documented
  limitation — see prior passes' findings), every state shown below was reached via the
  `EXTRA_SHORTCUT` intent contract (`measure/ring`) plus `run-as` writes to `files/settings.json` /
  `files/ring_bank.json` (demo mode, theme, calibration, a synthetic 5-entry pinned bank, and the
  orientation angle) rather than tapping through the UI — device state was restored to a plain
  default (demo off, no calibration, original theme, angle 0°) at the end of the session.
  Verified this way: Analyzer idle/measuring/demo/clipping-scale states; the full circular layout
  filling the display with a clear gap between the level meter and spectrum; Ring with 0, 2 (mid-demo,
  live), and 5 (injected pinned bank) populated tiles, including the most-prominent outline and the
  "all 5 pinned" message; ICE_CYAN theme + Dim together; a calibrated Estimated-SPL reading and its
  correspondingly-scaled level meter; and rotation at 0°/45°/90°/180°/270°, including that the
  persisted angle survives a fresh cold launch and that page-swipe/back gestures are unaffected.

## Next concrete action (physical steps only you can do)
1. **Real touch interaction** on the actual hardware — tap-to-toggle-pin and long-press-to-inspect on
   the new Ring tiles, and the tap-to-select-band gesture on the Analyzer spectrum, could only be
   verified by code review this pass (the `combinedClickable`/`detectTapGestures` wiring, unchanged in
   kind from what already worked pre-redesign), not by an actual on-device tap — this device's known
   synthetic-touch limitation applies here same as every prior pass.
2. **Crown feel**: confirm the rotation sensitivity (`OrientationMath.DEGREES_PER_ROTARY_PIXEL =
   0.24`, an initial engineering value) feels proportionate on a real crown turn, not just via the
   persisted-angle screenshots this session used to verify the rendering/persistence mechanism itself.
3. **192dp/227dp round screens** — only the one connected 240dp device was available this session;
   `InstrumentGeometryTest` checks the geometry math scales correctly across a range of radii, but the
   actual text/tile fit at the smaller sizes is unverified on real hardware.
4. Re-run the full manual checklist in `docs/MEASUREMENTS.md` (updated this pass with new rotation/
   level-meter/Ring-grid items) on real hardware with real acoustic input, not synthetic Demo-mode
   signal.

## Prior passes — unchanged this session except where called out above
See git history for the three earlier passes this one builds on: the original visual redesign + Ring
rework (black/live-green/held-red system, `HorizontalPager`, `CaptureSession` sharing); the app-logo +
Tile/complication pass; and the "polished redesign" pass (combined circular Analyzer replacing
separate Level/Spectrum pages, 5 color themes + Dim, the guided 4-step calibration flow, the
independently-pinnable 5-slot Ring bank with a most-prominent-ring complication selector, and
`ShortcutRequest` as a pure/unit-tested router). Nothing from those passes' own behavior contracts
changed this session except the Analyzer's layout, the Ring page's main-screen content, and what the
crown does — the underlying DSP, `RingTracker` capacity/eviction/dedup/most-prominent rules,
calibration flow, themes, and Tile/complication contract are all exactly as those passes left them.
