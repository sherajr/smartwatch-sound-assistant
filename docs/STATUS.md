# Status

## Watch dictation replaces record-and-transcribe (voice input repair)

**Why:** on the owner's Pixel Watch 5 the assistant recorded the question but never got words back. The old route first required `SpeechRecognizer.isOnDeviceRecognitionAvailable()`; on that watch the only recognition service is Google TTS's `GoogleTTSRecognitionService`, so that check very likely failed on that watch, and the fallback (record, send audio to the phone, the phone's own on-device recognizer, optional OpenAI upload) had the same prerequisite on the phone. The watch's real dictation screen was never tried: `RECOGNIZE_SPEECH` resolves to Gboard's `WearRemoteInputActivity`. (That is the cause the device evidence points to; it was not proven by instrumenting the old build.)

**What it does now** (details: [AI_ARCHITECTURE.md §7](AI_ARCHITECTURE.md#7-voice-input-and-speech)): tap the mic → the measurement is snapshotted, measurement is paused through an `AudioCoordinator` lease, and the watch's own dictation screen opens through an Activity-level `ActivityResultLauncher` → the text comes back to the **watch**, you check it → only **Send** (or **Log**) goes on: the phone gets the reviewed text + the pre-speech measurement, calls the selected provider, and answers as before. **Type instead** (the watch's `RemoteInput` screen) uses the same session. StageScope never records you and never sends audio to the phone; the dictation service itself may use Google's servers (Settings says so in one line).

**Changed:** watch `assistant/speech/` (new `DictationController` state machine, `DictationLauncherBinding`, `DictationIntents`/`Results`/`Presentation`, `DictationStore`; `SpeechInput` and `VoiceRecorder` deleted), `AudioCoordinator` (no-timer dictation lease, `release(id, allowResume=false)`, `isHeld`, cancellation-safe `acquire`), `AssistantViewModel`/`ListenScreen`/Tasks/Memos/Settings screens, `AssistantRepository` (upload path removed, `migrateLegacyMemos`), `DataLayerPhoneLink`/`PhoneLink` (no `sendVoice`), `MainActivity`, `AppContainer`, manifest `<queries>`, `wear-input:1.2.0`; phone `PhoneMessageHandler` (refuses `VoiceOffer`), `WatchListenerService` (closes a voice channel unread), `PhoneContainer`/`Workers` (cancels queued `TranscribeWorker` jobs by tag; no recognizer, no uploader), Home/Settings screens, manifest (`RECORD_AUDIO` removed); `Wire` types kept so old payloads decode; docs and screenshots.

**Existing recordings:** recordings an earlier version made are kept exactly as they were as *Older recordings* (never uploaded, deleted only by you); transcripts already made can still be reviewed as text; one unsent dictation survives closing the app.

### What was actually run
- **Unit tests: shared 106, phone 184, watch 322 — all pass** (watch was 241 before; the voice-memo upload tests were replaced by migration / "nothing uploads" tests; the transcriber tests were deleted with the transcribers). The new controller tests were validated by deliberately breaking six rules and confirming each is caught. Source guards fail if a recognizer, recorder, voice offer or channel call comes back.
- **Lint: 0 errors** on both apps (16 / 15 pre-existing dependency-version and style warnings). One `InvalidFragmentVersionForActivityResult` false positive (a transitive `androidx.fragment:1.2.4`; the host is a plain `ComponentActivity`) is suppressed on `DictationLauncherBinding` with the reason.
- **Builds:** both debug APKs; `check-signing.ps1` — same certificate (SHA-1 `0B:05:6D:E5:92:4C:52:10:95:5B:F8:A0:FC:BA:A9:B2:72:82:9F:66`, unchanged).
- **Instrumented (Wear emulator, fake recognition Activity):** 7/7 — real Activity Result API, cancel, **host recreation while the screen is open**, missing handler, `RemoteInput` result shape, the exact intent. The fixture activities live in `app/src/debug` (they must run in the app's process; inert, not exported).
- **Real Pixel Watch 5 (Android 17) and Pixel 10, installed in place over the previous build (no uninstall):** dictation resolves and opens from the app; stays open past the launch bound; BACK = cancelled → Assistant page; measurement running + spectrum frozen → dictate → back → still measuring, still frozen; a real `RESULT_OK` text came back and was handled (the question reached the AI and was answered); a fixture recording left by the old version was migrated and **not uploaded** while the phone was reachable; the updated phone app starts cleanly and no longer requests the microphone. **This pass found a real bug the JVM tests could not:** a launch-timeout watchdog armed *after* the state was published overwrote the listening watchdog (the host claims inside the publication), killing sessions 8 s in. Fixed, with a regression test that reproduces the re-entrancy.
- **Observed, not controllable:** the system dictation screen opens an audio-playback stream when it ends (a platform sound); it also starts listening by itself and shows its own "Didn't catch that. Try speaking again".

### Still needs you (a person speaking)
See [AI_SETUP.md §11 "Speech — watch dictation"](AI_SETUP.md#11-manual-verification-checklist-for-you): spoken dictate → review → Send → answer; spoken *Log an issue*; cancel then retry; pin a ring + Freeze + dictate; phone off; with and without internet (how the system screen behaves offline was not tested); an old recording with the phone reconnecting; any dictation-screen sound in the theatre. Also not verified: spoken recognition quality, and a real watch with *no* dictation handler (only the emulator with its handler disabled).

---

## Optional AI assistant + phone companion (all additive; nothing here is committed yet)

The watch gains a third page, **ASSISTANT** (after ANALYZER and RING), and the repo gains a phone companion and a shared module.
The instruments are unchanged and still need no phone, network, key or Google account. Design and guarantees:
[AI_ARCHITECTURE.md](AI_ARCHITECTURE.md); owner setup: [AI_SETUP.md](AI_SETUP.md); screenshots: `docs/screenshots/assistant/` (watch) and
`docs/screenshots/phone/`. Work is on branch `feature/ai-assistant-phone-companion`, **uncommitted**.

### What was built
- **`:shared`** (pure Kotlin) — wire protocol v1, `MeasurementContext`, the conflict-safe issue log (version vectors, tombstones), the action state machine (confirmation bound to action id + revision + content hash + Google account), show/report/time logic, `PersistentState` (atomic writes, corrupt-file quarantine).
- **`:phone`** (same package name and signing key as the watch app) — four real provider adapters (OpenAI, Gemini, xAI Grok, Anthropic Claude: streaming, tool lifecycle, cancellation, usage, errors; never a silent provider or model switch), Keystore-encrypted bring-your-own keys, a typed tool registry the model can only *propose* through, Gmail/Calendar actions behind UI confirmation (an ambiguous send is "outcome unknown" and never retried), Keep as an honest "Ready on phone" hand-off, show/contacts/issues/usage screens, voice transcription, "Continue on phone" deep links, WorkManager-backed durable requests.
- **Watch** — the Assistant page and its sub-screens, `AudioCoordinator` (the assistant borrows the mic from measurement and gives it back), a pre-speech `MeasurementContext` snapshot (raw dBFS and Estimated SPL kept apart; pinned/restored rings never described as "ringing now"), a durable outbox with stale-question rules, offline voice memos and a local issue log, an optional second Tile ("Ask AI") that only ever opens the page.
- **Tooling and docs** — `scripts\` build/test/lint/install for both apps with device-role checks and `check-signing.ps1`, VS Code tasks, README, `AI_SETUP.md`, `AI_ARCHITECTURE.md`, updated `CLAUDE.md`.

### Verification (what was actually run)
- **Unit tests: shared 106, phone 182, watch 241 — all pass** (the 113 original watch tests are untouched). **Lint: 0 errors** on both apps (warnings are dependency-version notices and style hints). Both debug APKs build; `check-signing.ps1` confirms they share one certificate (debug SHA-1 `0B:05:6D:E5:92:4C:52:10:95:5B:F8:A0:FC:BA:A9:B2:72:82:9F:66`).
- **Emulators** (no accounts, no real keys, fixture data only): every watch Assistant screen was driven by touch on a round Wear OS 6 emulator; the Analyzer was confirmed to pause and resume around a question; the phone app was run end to end in its built-in test mode (a conversation through the real orchestrator and stores), with show/contact/issue forms, a dummy key encrypted by the real Android Keystore (masked, never in plaintext on disk or in logcat, removable), corrupt-file recovery, and deep links to a conversation and to a single card. Details: [AI_ARCHITECTURE.md §17](AI_ARCHITECTURE.md#17-what-is-and-isnt-verified).
- **Defects that looking at the screens found, now fixed** (each has a note in `CLAUDE.md`): a cold-launch shortcut landing one page short ("Ask AI" opened Ring); a failure message from one screen reappearing on another; Confirm appearing to do nothing when the draft was scrolled; warnings placed after the text they warn about; voice memos and issues deleted by a single tap; long answers clipped by the bezel; a recording silently dropped to make room for a newer one; and on the phone, dialog errors hidden below the visible fields (an invalid time zone just ignored Save), a link that opened the right conversation but not the right card, and test-mode answers counting against the real daily request limit.

### Not verified here — needs your hardware or accounts
Live calls to any AI provider (the adapters are tested against fixtures written from the vendors' documented formats, last checked 2026-10-01); Google consent and real Gmail/Calendar; Wear Data Layer delivery and the voice channel between a **real paired** watch and phone (the two emulators are not paired); on-device speech recognition and text-to-speech (the emulator has neither); notifications; Keep/Gemini; the Ask Tile's rendering; and the layout on a physical round watch.

### Things you should know
- **A documentation mismatch was corrected, not a behaviour change.** README and `MEASUREMENTS.md` said backgrounding the app "stops capture" and shows "Paused"; the code never did that (only the 2-minute keep-awake countdown stops a session, and Android may silence the mic once the app is off screen). The docs now say what the code does. "Paused" now exists, but only for the assistant borrowing the mic.
- `scripts\install-launch.ps1` had a bug when exactly one device was attached (it indexed a one-item result as an array); fixed while adding the role checks.
- **One watch listed twice by adb.** On the owner's real setup `adb devices` showed the Pixel Watch 5 both as `192.0.2.10:40000` (an example address) and as an mDNS `adb-<serial>-xxxxxx._adb-tls-connect._tcp` entry, and the install script refused with "More than one watch is connected". Entries are now merged by hardware serial (`ro.serialno`) so that is one watch (the `ip:port` entry is used; a cable would beat both), while two different watches — or two emulators — are still refused. Covered by `scripts\test-device-selection.ps1`, which reproduces the exact error without the fix.
- Speech: **superseded** — see "Watch dictation" at the top of this file. (This line used to say only the on-device recognizer was used and that the phone transcribed recordings; neither is true any more.) Theatre mode (silent) is the default; text-to-speech is on demand and asks first with no headphones connected; haptics are off.
- Model names and prices come from a catalogue dated 2026-10-01 and are shown with that date. Each provider bills *your* account; the app's limits only limit what the app sends.

### What you still need to do (in order)
1. Install both apps (`scripts\build.ps1`, `scripts\install-phone.ps1`, `scripts\install-launch.ps1`) and run `scripts\check-signing.ps1` — the two must be signed with the same key or the watch and phone will never connect.
2. Open the phone app → Settings → AI providers, paste a key for at least one provider (OpenAI, Gemini, xAI or Anthropic) and use *Check key*.
3. Optional, for real email/calendar: create a Google Cloud project, enable the Gmail and Calendar APIs, create an **Android** OAuth client for package `com.peaceantz.stagescope` with the SHA-1 above, add yourself as a test user, then connect in Settings → Gmail & Calendar ([AI_SETUP.md §5](AI_SETUP.md#5-gmail-and-calendar-optional)). Until then drafts open in your email/calendar app instead.
4. Work through the manual checklist in [AI_SETUP.md §11](AI_SETUP.md#11-manual-verification-checklist-for-you) on the real watch and phone, including the round-screen checks.
5. Nothing has been committed. Review `git status`, then commit when you're happy.

## Simplified Ring interactions + fixed Calibration overlap

Four changes: "Clear unpinned" is now a second button right on the Ring page next to Start/Stop;
a pinned tile's fill color now visibly differs from an unpinned one; the separate Captures screen
is gone (all routine capture management is on the Ring page itself, with "Clear all" moved to Ring
Details); and Calibration's reference-reading value/buttons no longer overlap.

### 1. "Clear unpinned" on the Ring page (`ui/ring/RingScreen.kt`)
- Added a second `CompactGlyphButton` ("✕") to Ring's lower action row, alongside Start/Stop —
  still within the round-screen two-button-per-row limit. Wired directly to the existing
  `RingViewModel.clearUnpinned()`/`RingTracker.clearUnpinned()` (unchanged — already removed only
  unpinned captures regardless of LIVE/HELD/EXPIRED state, and already dropped any in-flight track
  linked to a cleared capture so a continuing tone must re-confirm rather than instantly
  reappearing). The button is disabled, not hidden, when `RingSnapshot.history` has no unpinned
  entries, so its position never shifts. No confirmation step and no navigation, per spec — this is
  a same-page, reversible-by-nature action (a cleared unpinned tone can simply reappear if it's
  still sounding).

### 2. Pinned tiles get a visibly different fill (`ui/ring/RingTilesGrid.kt`)
- `RingTile`'s background now switches between `palette.Surface` (unpinned) and `palette.HeldDim`
  (pinned) — previously it stayed `palette.Surface` regardless of pin state, so only the small
  status-label color/glyph changed, which on-device was easy to miss. `palette.HeldDim` (not the
  brighter `palette.Held`) was chosen specifically so the existing near-white primary-text color
  stays readable against it in all 5 themes, without touching the text color itself. The existing
  "◆" prefix on the status label remains as the non-color cue. The most-prominent outline
  (`palette.Live`, unchanged) stays a completely different visual channel (border vs. fill) from
  the pinned state, so a tile can be most-prominent, pinned, both, or neither without ambiguity —
  confirmed on-device (see below).

### 3. Removed the separate Captures screen (`ui/ring/RingCapturesScreen.kt` deleted)
- Deleted the screen, its `ROUTE_RING_CAPTURES` route/composable, `RingScreen`'s `onOpenCaptures`
  parameter, and `RingTilesGrid`'s `combinedClickable`/`onLongClick`/`onInspect` plumbing — a tap is
  now the tile's only gesture (`clickable`, not `combinedClickable`). `RingDetailsScreen` gained
  "Clear all" (confirm-gated, explicitly labeled "also removes pinned") moved over from the old
  screen's `clearAllArmed` pattern, alongside the pre-existing "Clear unpinned". Updated every
  accessibility description, doc comment, README passage, and `docs/MEASUREMENTS.md` passage
  (including the manual checklist) that referenced opening Captures, long-pressing a tile, or
  selecting a frequency before pinning.

### 4. Fixed Calibration's reference-reading overlap (`ui/settings/CalibrationScreen.kt`)
- Root cause: `ReferenceReadingControl` emitted a `Box` (the value) followed by a `Row` (the ±
  buttons) as two *sibling* composables with no enclosing `Column` — inside a `ScalingLazyColumn`
  `item {}`, sibling root composables don't stack, they overlap at the item's own top-left, which is
  exactly what let the ± buttons cover the numeric reading. Fixed by wrapping both in a `Column`
  with explicit `Arrangement.spacedBy` and centering; the value now uses `frequencyStyle()`
  (30sp, centered, `fillMaxWidth()`) so it's unambiguously the primary readout, with the rotary/focus
  modifiers moved onto the value `Text` itself (same scope as before, just no longer a bare `Box`).
  The exact same sibling-overlap shape existed in the Measure step's "Sampling…"
  text/progress-bar/status block (three siblings in one `item {}`) — fixed the same way with a
  `Column`. Audited every other `item {}` in the file; nothing else emits more than one root
  composable outside an explicit `Row`/`Column`. The "Reference meter reading" label was already a
  separate preceding list item, so the required label → value+unit → button row → instructions
  → actions order falls out without further changes.

### On-device verification (same connected Pixel Watch 5-class device as prior passes)
- `-r` reinstall (preserves app data) + launch via `adb`, confirmed as the resumed foreground
  activity with no crash in logcat.
- Per this project's documented synthetic-touch limitation, Ring's tap-to-pin/unpin and
  Calibration's ± buttons could not be exercised via `adb input tap`. Verified instead via
  `EXTRA_SHORTCUT=measure` (starts the shared session) + `EXTRA_SHORTCUT=ring`, with Demo mode
  toggled on through `run-as`-written `settings.json` and the watch's own pre-existing
  `ring_bank.json` (3 previously-pinned captures from earlier sessions) providing a live mix of
  pinned/unpinned tiles. A screenshot confirmed: all 5 tiles rendered 2-1-2; the 3 restored-pinned
  tiles showed the new reddish `HeldDim` fill with the "◆" cue while the 2 live unpinned demo tiles
  stayed dark; the most-prominent tile's green outline was clearly a different visual channel from
  the pinned fill; the lower row showed exactly two full, unclipped circular buttons (Stop, Clear
  unpinned) with Clear unpinned correctly enabled (unpinned captures were present). Settings were
  restored to their prior value (demo mode off) and the app force-stopped afterward, matching this
  project's established practice of leaving the device in its prior state. Calibration has no
  shortcut route (by design, per `ShortcutIntents.kt`) and Details/Calibrate requires a real tap to
  reach, so its fix is verified by layout reasoning + code review, not an on-device screenshot —
  same limitation prior passes recorded for real touch interaction.
- Noted, not investigated further: after this session's testing, the watch's `ring_bank.json`
  differed from what this pass's own code wrote (no code path other than explicit
  `pin`/`unpin`/`clearSelected`/`clearAll` persists that file, and none of those were invoked via
  shortcut). Most likely explanation is the physical watch being tapped directly by its owner during
  the session (genuine touch works fine on this hardware; only synthetic `adb`-injected touch does
  not) rather than anything introduced by this pass — flagged here for visibility, not corrected,
  since overwriting it could discard real, intentional pin changes.

### Checks run this session
- `scripts\test.ps1` (`gradlew testDebugUnitTest`) → BUILD SUCCESSFUL. Added regression coverage:
  `RingTrackerTest` — five captures/two pinned survive `clearUnpinned()` exactly, `clearUnpinned()`
  on an empty or fully-pinned bank removes nothing, and two toggles (pin then unpin) on the same
  capture return it to unpinned; `RingSlotAssignerTest` — clearing unpinned captures out of a full
  bank leaves the still-pinned ones in their original slots.
- `scripts\lint.ps1` forced fresh (`--rerun-tasks`, not the cached report) → BUILD SUCCESSFUL, **0
  errors, 12 warnings**, all pre-existing dependency-freshness/deprecation notices — none introduced
  by this pass.
- `scripts\build.ps1` → BUILD SUCCESSFUL. APK: `app\build\outputs\apk\debug\app-debug.apk`.
- Found, did not fix (out of scope for this pass): `scripts\install-launch.ps1` throws when exactly
  one device is attached — PowerShell collapses a single-element pipeline result to a bare string,
  so `$deviceSerials[0]` indexes into the *string's characters* instead of an array, picking the
  single letter "a". Installed/launched directly via `adb install -r` / `adb shell am start` instead
  this session.

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
