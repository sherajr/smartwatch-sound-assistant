# Status

## Done (this pass — app logo)
Replaced the placeholder system icon (`@android:drawable/ic_btn_speak_now`) with a real logo the
user supplied (ring + level-meter bars in the app's own live-green/held-red, matching
`StageScopeColors` exactly). Source was a flat PNG on a black background; de-matted it
programmatically (per-pixel `alpha = max(r,g,b)`, then unpremultiplied) into a clean transparent
master (`docs/stagescope_logo.png`) rather than using the user's own transparent export, which had
visible edge-fringing artifacts.
- **Launcher icon**: proper adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` +
  `ic_launcher_round.xml`, `@color/ic_launcher_background` = black, foreground PNGs at all 5
  densities in `mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher_foreground.png`, content scaled to 62% of the
  108dp canvas so it survives any mask crop) plus a `<monochrome>` variant for Android 13+ themed
  icons, reusing the same white silhouette made for the complication.
- **Complication/Tile assets restyled from the same source**: `ic_stagescope_mono.png` (white
  silhouette, required for the complication's `android:icon`/`MonochromaticImage` since those must
  be single-color) replaces the earlier hand-drawn placeholder vector bars; `ic_stagescope_logo.png`
  (full color, no white-only requirement for Tiles) replaces it for the Tile service's own
  `android:icon` and `androidx.wear.tiles.PREVIEW` metadata.
- All raster assets generated with a small one-off Java/`ImageIO` program (no ImageMagick/PIL
  available in this environment) rather than hand-authored, so they're pixel-accurate derivatives
  of the actual supplied logo, not an approximation.
- **Lint-driven fixes**: the Tile preview PNG had to be ≥384×384px (was 288, lint caught it, bumped
  to 432); `SquareAndRoundTilePreviews` wants the Tile preview in both `drawable-round` and
  `drawable` variants (used `drawable-round-nodpi`/`drawable-nodpi` to also satisfy the separate
  `IconLocation` densityless-folder check). Tried renaming `mipmap-anydpi-v26` → `mipmap-anydpi`
  per lint's `ObsoleteSdkInt` suggestion (minSdk 30 already implies v26+) — this pinned AGP/aapt2
  toolchain doesn't accept the unqualified folder for an adaptive-icon XML (`resource mipmap/
  ic_launcher not found`), so reverted; left as one accepted warning rather than break the build.
- **Checks**: `assembleDebug`/`lintDebug`/`testDebugUnitTest` all green — **0 lint errors, 12
  warnings** (10 pre-existing + the one reverted `ObsoleteSdkInt` + one `IconDuplicatesConfig`,
  correctly flagging that the round/square Tile preview are intentionally identical), **49/49
  tests**. Installed on the watch; confirmed via `adb shell am start
  -a android.settings.APPLICATION_DETAILS_SETTINGS` that the app still launches correctly and shows
  its label under the new manifest icon attributes (`docs/screenshots/app_icon_check.png`) — **did
  not get a clean live screenshot of the icon itself** in the app drawer/launcher grid; this Wear OS
  build doesn't expose that screen to simple scripted `adb shell input` navigation (tried `KEYCODE_
  APP_SWITCH`/recents, the settings app-info page, home; none surfaced it). The icon's actual pixels
  were verified instead by compositing the generated PNGs onto black locally before installing
  (clean, well-centered, no clipping at any density) — real, but not the same as seeing it rendered
  by the watch's own launcher.

## Done (this pass — Tile + watch-face complication)
- **Implementation choice**: stable Tiles + ProtoLayout, not a separate "Wear Widgets" stack. The
  connected Pixel Watch 5 (API 37, Wear system build 1.35.84) runs
  `com.google.android.wearable.protolayout.renderer` and its own `gridlauncher` as the Tile
  carousel host, confirming Tiles/ProtoLayout is the live rendering pipeline on real hardware, not
  a legacy shim. `androidx.wear.tiles.TileService` remains the system-binding contract; the actual
  layout is built with `androidx.wear.protolayout` + `androidx.wear.protolayout.material3` (stable
  1.4.2 — `primaryLayout`, `textDataCard`, `buttonGroup`/`compactButton`, `textEdgeButton`), which
  reuses `StageScopeColors` directly as a `ColorScheme`. One Tile, one carousel entry — no
  duplicate Tile/Widget registration.
- **StageScope Tile** (`widget/tile/StageScopeTileService.kt`): header text, a compact data card
  with the last saved RMS reading (exact value + unit, "Last reading · <time>"), a one-line ring
  summary ("PINNED · 326 Hz" / "Last ring · …", omitted entirely — not em-dashed — when there's no
  saved ring), a 2-button `buttonGroup` (SPECTRUM/RING shortcuts), and a bottom `textEdgeButton`
  ("MEASURE") as the one prominent action — never 3 buttons in a row. Empty state shows "Ready to
  measure" instead of the card. Timestamps use `widget/WidgetFormatting.kt`'s `formatWhen` (clock
  time same-day, date otherwise) so a cached render never silently shows a stale "x seconds ago".
- **StageScope complication** (`widget/complication/StageScopeComplicationService.kt`): SHORT_TEXT
  ("326Hz"/"2.2kHz" + "PIN"/"LAST" title, kept under the SHORT_TEXT length limit) and
  MONOCHROMATIC_IMAGE, both sourced from the same pinned-else-last-seen ring summary. No-data state
  shows a plain "StageScope"/mark + opens LEVEL (never starts the mic). Accessible descriptions
  spell out the full frequency, pinned/last status, and save time.
- **Shared summary, not a second database** (`data/SurfaceSummary.kt` + `SurfaceSummaryRepository`):
  same plain-JSON-in-`filesDir` pattern as `SettingsRepository`/`SnapshotRepository`. Written only
  at meaningful events — `LevelViewModel` on Stop (skipped entirely for demo-mode readings),
  `RingViewModel` when the pinned-or-most-recent capture's *identity* changes (a new confirm, a
  pin/unpin, a clear) — diffed against the last-persisted id+pinned key so a live stream of
  detector updates at the existing ~10 Hz UI cadence never turns into continuous writes or provider
  updates. `RingTracker` itself is untouched; the ViewModel only reads `RingSnapshot.history`.
  `widget/SurfaceUpdateNotifier.kt` calls `TileService.getUpdater(...).requestUpdate(...)` and
  `ComplicationDataSourceUpdateRequester.requestUpdateAll()` right after each write.
- **Entry-point routing, once each** (`ui/nav/ShortcutIntents.kt`, `MainActivity.kt`,
  `StageScopeNavHost.kt`): Tile/complication taps carry a `EXTRA_SHORTCUT` string extra (a Tile's
  declarative `ActionBuilders.launchAction` can only specify package/class/extras, not a custom
  Intent action, so both surfaces route through the same extra-based contract rather than distinct
  actions). `MainActivity` only reads the launch intent when `savedInstanceState == null` — Android's
  own signal that this is a genuinely fresh creation, not a rotation or a process-death respawn
  replaying the same stale `Intent`; a real re-tap always arrives through `onNewIntent`, which is
  unconditionally honored. The nav host applies the request (pager page, optional Measure-start via
  the existing `rememberAudioPermissionRequester` path — same permission flow the in-app Start
  button uses — optional Ring capture re-selection) in one `LaunchedEffect` keyed on a per-delivery
  id, then clears it. Reusing an already-running session, preserving Spectrum/Ring's cursor and
  selection, and never auto-starting the mic from a bare "open" tap all fall out of this without
  special-casing, because `CaptureSession.start()`/`RingTracker.selectCapture()` were already
  idempotent/no-op-safe.
- **"Watch shortcuts" help screen** (`ui/help/WatchShortcutsHelpScreen.kt`): static instructions for
  the manual "swipe to the Tile carousel → Add" and "long-press watch face → Edit → complication
  slot" steps, reachable from LEVEL's Details screen. There is no public system API for an app to
  pin its own Tile or become a chosen complication automatically.
- Both services' manifest `android:icon` and the Tile's `androidx.wear.tiles.PREVIEW` metadata
  drawable originally pointed at a hand-drawn placeholder vector mark; both now use the real logo
  assets described in the "app logo" section above (`ic_stagescope_mono`/`ic_stagescope_logo`).
- Manifest: both services declared `exported="true"` with the correct system-only bind permissions
  (`BIND_TILE_PROVIDER` / `BIND_COMPLICATION_PROVIDER`), correct intent-filter actions, and
  `SUPPORTED_TYPES=SHORT_TEXT,ICON` + `UPDATE_PERIOD_SECONDS=0` (no polling — updates are pushed
  explicitly by `SurfaceUpdateNotifier`, matching the "no per-second polling" requirement).
- New pinned dependencies (`app/build.gradle.kts`): `androidx.wear.tiles:tiles`/`tiles-material`
  1.6.2, `androidx.wear.protolayout:protolayout`/`protolayout-material3` 1.4.2,
  `androidx.wear.watchface:watchface-complications-data-source-ktx` 1.3.0,
  `androidx.concurrent:concurrent-futures` 1.3.0, and `com.google.guava:guava:33.7.1-android`. The
  guava dependency is a non-obvious requirement: Tiles' `onTileRequest` returns
  `com.google.common.util.concurrent.ListenableFuture`, and without real Guava on the classpath,
  Guava's own published Gradle metadata substitutes the lightweight `listenablefuture:1.0` stub with
  an intentionally *empty* artifact (`9999.0-empty-to-avoid-conflict-with-guava`), so the type fails
  to resolve at all — a known Gradle/Guava interop gotcha, not something specific to this project.

## Checks run this session
- `gradlew testDebugUnitTest` → BUILD SUCCESSFUL, **49/49 passed** (the prior 43 DSP/RingTracker
  tests, unchanged and unaffected, plus 6 new `WidgetFormattingTest` cases covering the same-day/
  older-date branch of `formatWhen`, the SHORT_TEXT 7-character budget and exact rounding of
  `formatFrequencyCompact`, `formatFrequencyReadable`, and `formatDbCompact`'s explicit sign).
- `gradlew lintDebug` → BUILD SUCCESSFUL, **0 errors**, 12 warnings — the same 10 pre-existing ones
  (Gradle/dependency freshness, `allowBackup` deprecation, missing `taskAffinity`) plus 2 from the
  app-logo work, both accepted deliberately (see that section): one `ObsoleteSdkInt` on
  `mipmap-anydpi-v26` that can't actually be fixed on this pinned toolchain, one
  `IconDuplicatesConfig` correctly noting the round/square Tile preview are intentionally identical.
- `gradlew assembleDebug` → BUILD SUCCESSFUL. APK: `app\build\outputs\apk\debug\app-debug.apk`
  (~45 MB debug/unminified — up from the prior pass mainly due to Guava; not addressed this pass
  since `isMinifyEnabled` is off for both build types already and enabling it is out of scope here).
- Installed and launched on the same **Pixel Watch 5** (`adb-68041WRDTW60EA-jm8Zm0…`, API 37) used
  last session. `adb logcat` around install/launch shows no crash for `com.peaceantz.stagescope`.
- **On-device, confirmed via adb (not a preview or a guess)**:
  - `dumpsys package com.peaceantz.stagescope` shows both new services registered with the correct
    intent-filter actions and bind permissions (`androidx.wear.tiles.action.BIND_TILE_PROVIDER` /
    `android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST`).
  - System `WearServices` log shows the platform itself querying
    `StageScopeTileService` for a preview resource and preview bitmap immediately after install —
    i.e. the system recognized and rendered the Tile without throwing.
  - `WearableService` log shows a `dataChanged` event for
    `/complication_providers/data/provider_app/com.peaceantz.stagescope/` — the platform registered
    the app as a complication provider.
  - The watch unlocked partway through the session, enabling real visual verification (below).
- **Rendered and inspected on the actual watch, via `adb shell am broadcast ... DEBUG_SURFACE
  --es operation add-tile ...` (`result=1`) then swiping to it from the watch face** (the paired
  `DEBUG_SYSUI show-tile` broadcast consistently timed out on this device/OS build — matches last
  session's note about this unit's testing friction — swiping worked reliably instead):
  screenshots in `docs/screenshots/tile_populated_state.png`. **This caught and fixed a real bug**:
  the first render had the reading card filled solid live-green with `filledCardColors()` (the
  default), the exact "oversized colored surface" anti-pattern `CLAUDE.md` already calls out for
  in-app buttons — `textDataCard` needed an explicit `colors = filledTonalCardColors()` the same way
  `DetailButton` needs `filledTonalButtonColors()`. Fixed, rebuilt, reinstalled, re-screenshotted:
  header mark + "STAGESCOPE", a subtle dark card ("Last reading · 6:23 PM" / "-66.4" / "dBFS"),
  SPECTRUM/RING compact buttons, and the green MEASURE edge button as the only vivid color on
  screen — matches the brief's hierarchy. Also switched the card to `DataCardStyle.
  smallCompactDataCardStyle()`: the default compact style's `NUMERAL_LARGE` title alone consumed
  most of the main content area and pushed the button row off-screen; `NUMERAL_MEDIUM` leaves room
  for everything. No clipping at the bezel in the final screenshot; ring-summary row correctly
  absent (no ring captured this session, not em-dashed).
- **Tap-through interactivity: attempted, inconclusive, flagged rather than assumed working.**
  `adb shell input tap` at both the MEASURE edge button and the RING compact button (verified
  against their on-screen positions) produced no navigation and no log activity at all for
  `com.peaceantz.stagescope` — the screen stayed pixel-identical to `tile_populated_state.png`.
  This may be a limitation of tiles reached through the `DEBUG_SURFACE` add-tile path specifically
  (not going through the normal add-from-picker lifecycle) rather than a real bug — the click
  wiring (`ActionBuilders.launchAction` + per-element `Clickable` ids) matches the documented API
  exactly and the equivalent complication `PendingIntent.getActivity` path is a much more
  well-trodden API — but this was **not proven working on-device** and should not be assumed so.
  See step 1 below.

## Next concrete action (physical steps only you can do)
1. **Glance at the app list** (press the crown/side button from the watch face) and confirm the new
   StageScope logo looks right there — the one thing about the icon swap not confirmed live
   on-device this session (this Wear OS build didn't expose the app-drawer screen to scripted `adb
   shell input` navigation; the icon's pixels were verified by local compositing instead, not by
   seeing it rendered by the watch's own launcher).
2. **Confirm tap-through the normal way**: swipe to the Tile carousel, scroll to the end, tap Add,
   choose StageScope (this exercises the real add-tile lifecycle, unlike the adb shortcut above),
   then tap MEASURE and confirm it opens the app on LEVEL and starts measuring. This is the one
   piece of the interaction spec (§3 in the original request) not yet confirmed on real hardware —
   see the inconclusive tap-through note above.
3. Long-press the watch face → Edit → pick a complication slot → choose StageScope, for both a
   SHORT_TEXT-supporting slot and an icon-only slot if the current face offers one; confirm the
   picker's preview (from `getPreviewData`) looks reasonable and the accessible description reads
   sensibly (e.g. via TalkBack or the complication picker's own description text), and that tapping
   it also opens the app correctly.
4. Tap MEASURE from the Tile with the app fully closed (cold start) and again with it already open
   in the background (warm/`onNewIntent`) — confirm both times it lands on LEVEL and a measurement
   starts without a second Start tap, and that backgrounding-then-reopening normally afterward does
   *not* restart a stopped session.
5. Get a real (non-demo) Ring capture once — a steady tone near the mic — and confirm both the Tile
   and complication pick up the frequency after the next `MEASURE`/pin/unpin, then tap the
   complication's frequency and confirm it opens RING with that exact capture selected; then
   force-stop the app and tap the complication again to confirm it opens RING plainly without
   resurrecting the old capture.
6. Everything from the previous pass's manual checklist (`docs/MEASUREMENTS.md`) that wasn't
   re-confirmed last session is still outstanding — this pass didn't touch measurement/ring code, so
   nothing new is at risk there, but it also wasn't re-verified again this session.

## Prior pass (visual redesign + Ring rework) — unchanged this session
New black/live-green/held-red visual system, `HorizontalPager` (LEVEL→SPECTRUM→RING) with capture
owned above the pager in a shared `CaptureSession`, and `RingTracker`'s track→capture pipeline
(350 ms confirm, 150 ms dropout tolerance, auto-hold, pin, bounded 8-record history). See git
history / prior revisions of this file for that pass's full notes; nothing in it was modified this
session except where called out above (`RingTracker` itself: untouched).
