# Measurement assumptions, limits, and manual test checklist

## Level (RMS / peak) — Analyzer page

- Samples are normalized 16-bit PCM in roughly [-1, 1]. RMS dBFS = `10*log10(mean(x^2))`; sample peak dBFS = `20*log10(max(abs(x)))`. Both are floored at -90 dBFS (`DbScale.FLOOR_DBFS`) — silence never displays as `-Infinity`.
- Verified invariant: a sine wave with peak amplitude 0.5 reads -9.03 dBFS RMS and -6.02 dBFS sample peak (`LevelMeterTest`).
- **Session energy-average** accumulates `sum(x^2)` and a sample count across all active (non-paused) blocks, and converts to dB once when read. It is explicitly *not* an average of per-block dB readings — averaging decibels is mathematically wrong and was a specific thing this app avoids (see `LevelMeterTest."session average accumulates energy, not decibels"`).
- This is unweighted (no A/C weighting), uses unwindowed samples, and is not LUFS, true peak, LAeq, or a certified acoustic measurement. It's labeled dBFS by default.
- Digital clipping is flagged when a sample's absolute value reaches `DbScale.CLIP_THRESHOLD` (0.998). Red/held-accent color is reserved for this and for other error states — never used to imply a hearing-safety judgement, and always paired with an icon+text label ("⚠ CLIP") so it doesn't read as the same thing as a HELD/PINNED ring.
- Sustained true-digital-zero (not just a quiet room) surfaces as "Silence — check mic route" after ~20 consecutive near-zero blocks, since that pattern is more consistent with a muted/misrouted input than an actually quiet room.
- **`AnalyzerReading` carries two RMS values on purpose**: `rmsDisplayDbfs` (calibration-offset applied, for the printed number/unit label) and `rmsRawDbfs` (always raw dBFS, for anything that needs a value on a fixed dBFS scale). Once calibrated, `rmsDisplayDbfs` can be a small positive "Estimated SPL"-range number — feeding that into a gauge built for a -90..0 dBFS range would pin it at 100% regardless of the actual signal, so any such gauge must use `rmsRawDbfs` instead.

## Calibration ("Estimated SPL") — guided flow

Reached from Analyzer Details → "Calibrate SPL". Four steps: **Prepare** (explains what calibration
does, offers "Use dBFS" to skip it, an expandable note on what a single offset can't fix) → **Enter
reference reading** (the SPL number read off a separate, trusted meter — not this watch; `+`/`−`
buttons and the crown adjust it while it's focused; an untouched example value cannot be silently
saved as a real reading, it must be explicitly adjusted or explicitly confirmed via "Use shown value
as my measured reading") → **Measure** (an explicit "Measure reference" action, ~3 second sampling
window) → **Review/Save** (reference reading, measured raw watch level, and the resulting
offset/alignment, with explicit Save and Retry actions, ending in an unmistakable "Calibration
saved. Level readings now show Estimated SPL." success screen with a Done action back to Analyzer).

- The reference meter must be set to **Z/unweighted, steady or slow response** — StageScope measures
  an unweighted signal, so an A-weighted reading is not an equivalent input and the flow says so.
- The Measure step's ~3-second sample is accumulated by `dsp/CalibrationSampleAccumulator`: energy
  (`sum(x^2)`) and a sample count across every block in the window, converted to dB **exactly once**
  at the end — never an average of per-block dB readings (`CalibrationSampleAccumulatorTest` verifies
  this against the same -9.03 dBFS invariant `LevelMeterTest` uses, and that a single loud block
  isn't washed out by a quiet one the way naive dB-averaging would).
- A completed measurement is rejected (Save disabled, reason shown) if: it clipped at any point in
  the window; its per-block dB spread exceeded `CalibrationViewModel.STABILITY_THRESHOLD_DB` (6 dB —
  "hold a steadier tone and retry"); the capture configuration changed since the sample was taken;
  Demo mode was on (either now or at the moment of measuring); more than
  `CalibrationViewModel.STALE_MS` (90s) has elapsed since it completed; or the reference reading was
  never explicitly touched/confirmed.
- Calibration stores a single offset: `offsetDb = referenceMeterReadingDb − measuredRawRmsDbfs`,
  tied to the exact capture configuration (`CaptureConfig.fingerprint()` = sample rate + audio
  source) it was measured against.
- The offset is applied **only** to Analyzer's RMS-derived readings (current, max, session average),
  which then relabel as "Estimated SPL". Sample peak stays dBFS always, and the radial spectrum is
  always raw dBFS — a single broadband offset does not make an instantaneous digital sample peak or
  a spectral trace into an acoustic SPL measurement.
- The offset is discarded automatically if the negotiated source or sample rate changes on a later
  Start (e.g. UNPROCESSED becomes unavailable and it falls back to MIC), reverting Analyzer to plain
  dBFS.
- What a single offset **cannot** correct for: the microphone's actual frequency response, any
  gain/AGC change, overload/clipping, or anything about accuracy beyond that one steady reference
  level. It is not a certified measurement and dBA is never displayed. The Calibrate flow itself says
  this plainly (Prepare step's expandable note, and the success screen).
- Calibration reuses the existing shared `CaptureSession` (never opens a second microphone); leaving
  the flow mid-measurement, backgrounding, a permission denial, or a config change mid-window all
  abort the in-progress sample rather than silently finalizing a partial one
  (`CalibrationViewModel.abortSampling`).

## Radial spectrum (Analyzer page)

- Hann window (`0.5 - 0.5*cos(2*pi*n/(N-1))`), FFT size 4096, 50% overlap (new frame every 2048 samples pushed into a 4096-sample sliding window) — unchanged from the prior Spectrum page's DSP.
- One-sided amplitude normalization: raw FFT magnitude × `2/sum(window)` for all bins except DC and Nyquist (× `1/sum(window)` for those two). A full-scale, bin-aligned sine reads ~0 dBFS at its bin (`SpectrumAnalyzerTest`).
- **Angle = logarithmic frequency, one consistent clockwise direction, DC excluded, capped at the negotiated Nyquist** — `ui/analyzer/RadialMapping.fractionForBin(bin, minBin=1, maxBin)` where `maxBin` is the actual Nyquist bin for the negotiated `sampleRate`/`fftSize`, never a fixed assumption. The ring is a **partial circle with a 90° gap centered at the bottom** (`RadialMapping.GAP_DEGREES`/`START_ANGLE_DEGREES`/`SWEEP_DEGREES`) where the two lower-row action controls sit.
- **Radius = amplitude on a fixed -90..0 dBFS scale** (`RadialMapping.radialFractionForDb`) — never autoscaled, so the visual never fabricates movement or exaggerates a weak signal.
- **Band aggregation**: the raw spectrum (up to 2049 bins) is reduced to `RadialMapping.DISPLAY_BAND_COUNT` (56) log-frequency-spaced display bands via **max aggregation** — each band takes the loudest of its underlying raw bins, never an average, specifically so a narrow one- or two-bin peak still reads at its true amplitude instead of being smeared down by quieter neighbors (`RadialMappingTest."band aggregation preserves a narrow one-bin peak..."`). Each band also records which exact raw bin produced its value.
- **Same-bin readout guarantee**: the selected band's on-screen frequency (`peakBin * binWidthHz`) and dB value (`band.magnitudeDbfs`) always come from that one real bin — the prior Spectrum page's readout mixed a `dominantFrequencyHz` value with a separately-sourced cursor-bin dB value, which this redesign deliberately does not repeat.
- Tap the ring (angle → `RadialMapping.fractionForAngle`, null if inside the gap) or turn the crown (`AnalyzerViewModel.moveCursor`/`setCursorArcFraction`) to move the selection; until the user does either, the cursor auto-follows the loudest band each frame.
- **Freeze is spectrum-only**: `AnalyzerViewModel.freeze()` stops pushing new samples into the FFT window and marks `SpectrumDisplay.isHeld = true`; the center RMS reading and Ring detection are entirely unaffected (they read from the same shared `CaptureSession` blocks independently) — the unit line shows "SPECTRUM HELD" so the two are never confused.
- Peak hold decays gradually (`PEAK_HOLD_DECAY_DB` per published frame) and is drawn as a muted outline distinct from the live bars; a compared snapshot draws as a dashed outline in the Held accent color; the selection cursor draws as a bright spoke — three visually distinguishable elements, plus the live bars themselves, none relying on color alone (dash style / fill vs. outline differ too).
- Snapshots store the full magnitude array plus sample rate, FFT size, source, and calibration state. Two snapshots (or a snapshot vs. the live spectrum) are only overlaid if `sampleRate` and `fftSize` match; otherwise the UI says so instead of drawing a misleading overlay.

## Find a Ring — five-slot capture bank

`dsp/RingTracker.kt` runs a four-stage pipeline (`RingTrackerSettings` centralizes every constant below):

1. **Observations** — per frame, strict local-maximum bins at least `contrastThresholdDb` (10 dB) above their neighborhood, gated by `minSignalGateDbfs` (-55 dBFS). Nearby bins from the same windowed lobe are consolidated (greedy non-max suppression) so one physical tone doesn't produce multiple observations.
2. **Tracks** (transient, not yet shown) — each observation is matched to the nearest existing track within a resolution-aware tolerance (`matchToleranceBins × binWidthHz`), or starts a new one. A track's frequency estimate is EMA-smoothed. It survives gaps up to `maxDropoutMs` (150ms) without losing identity or resetting its confirmation clock; a gap longer than that discards it. It confirms once it has held for `confirmDurationMs` (350ms) of cumulative evidence.
3. **Captures** — on confirmation, the track's frequency is **frozen** into a `RingCapture` (the live track can keep drifting independently). If a capture already exists near that frequency, the existing record is updated in place instead of creating a duplicate — the dedup tolerance (`dedupToleranceBins × the frame's actual binWidthHz`) is resolution-aware, not a fixed Hz-per-bin assumption. Silence does not erase a capture — it just stops updating `lastSeenAtMs`.
4. **Bank capacity (five slots, `maxCaptures`)** — a confirming tone fills an empty slot first; once full, it evicts the oldest **expired, unpinned** slot if one exists, else the **weakest unpinned** slot but only if it's at least `replacementMarginDb` (6 dB) stronger (avoiding constant churn between similar-strength tones); a pinned slot is never evicted, and if all five are pinned the tone is confirmed as a live track but simply doesn't claim a bank slot (`RingSnapshot.allSlotsPinned` flags this so the UI can say "All 5 pinned — unpin or clear one to capture another").
5. **Selection vs. pin vs. most-prominent** — three independent concepts, deliberately not conflated:
   - **Selected capture** (`selectCapture`/`heroCapture`) — whichever the user is currently looking at on the Ring page. State is SEARCHING (nothing yet) / DETECTING (an unconfirmed track exists) / LIVE (matched within the last `maxDropoutMs`) / HELD (past that but within `autoHoldMs`, default 20s, configurable 10/20/30s) / EXPIRED (past `autoHoldMs`, unpinned — shown "HISTORICAL") / PINNED (stays selected regardless of hold expiry) / PAUSED.
   - **Pinned** (`pin(id)`/`unpin(id)`/`setPinned(id, Boolean)`) — **independent per capture**. Pinning one never unpins another; any of the 0–5 slots can be pinned in any combination. Pinned captures are persisted (id/frequency/save-time, never audio) via `RingBankRepository` and restored at startup via `RingTracker.restoreCapture()`, marked `restoredFromDisk` (shown "SAVED" in the Captures list) until a live detection updates them again, with ids that never collide with a freshly-confirmed capture's id.
   - **Most prominent** (`RingSnapshot.mostProminentCaptureId`) — the strongest currently-**LIVE** confirmed capture by smoothed spectral prominence, entirely independent of the selected capture and of any pinned flags. Switches promptly (no dwell) to a clearly stronger candidate (≥`prominenceSwitchMarginDb`, 4 dB); a near-equal candidate must lead for `prominenceDwellMs` (600ms) before taking over — hysteresis against flapping between two similar tones. Sticky when nothing is currently live (keeps returning the last id it held, so callers can show it as cached/"Last"), and cleared when that specific capture is removed by a clear or an eviction. **This — not "pinned, else most recent" — is what the watch-face complication shows** (see below); manually selecting or pinning a capture never redefines it.

All timing uses an injected `MonotonicClock` (`System.nanoTime()`-based in production), never a frame counter, so behavior is identical whether frames arrive at 10Hz or in a burst. Full behavioral contract — jitter, transient rejection, dwell timing, dropout tolerance, held-frequency freezing, auto-hold expiry/reselection, recurring-tone dedup (resolution-aware), five simultaneous/sequential captures, a sixth candidate's eviction rules, all-five-pinned behavior, clear-unpinned, most-prominent selection/hysteresis/stickiness, restore-from-disk id stability — is in `RingTrackerTest`, including a full-pipeline acceptance test (real FFT + synthesized PCM) for the spec's "2.15kHz for one second, then silence, then a separate 3.2kHz tone" scenario.

This is a heuristic on spectral shape and time alone. It cannot distinguish acoustic feedback from a sustained musical tone, does not identify a source, and never suggests an EQ cut — the UI always labels results "Possible ring" with that caveat visible in Ring's details screen. Haptics are not implemented at all this pass (satisfies "default off" trivially).

## The watch-face complication and Tile: cached data only

- `SurfaceSummaryRepository`'s `RingSummaryState` is written from `RingViewModel.resolveComplicationCapture()`, which reads `RingSnapshot.mostProminentCaptureId` (falling back to the strongest remaining historical capture if that specific id was cleared) — **not** "pinned, else most recent". A change driven purely by the live audio stream (no Pin tap at all) still reaches the complication, because the diff key (`captureId, pinned, frequencyHz`) is recomputed from this resolver on every published snapshot.
- The complication and Tile **never** open the microphone, start background listening, or run FFT analysis — they read `SurfaceSummaryRepository`'s already-computed, disk-backed state only. Wording always says "Last analyzed <time>" (via `widget/WidgetFormatting.formatWhen`), never implying the watch face is actively listening.
- A tap opens Ring and re-selects the matching capture if it's still in the live session's bank (`RingViewModel.selectCapture` is a no-op if the id isn't found — handles a historical/since-cleared capture gracefully); a no-data tap opens Analyzer without starting the microphone.
- Icon-only (`MONOCHROMATIC_IMAGE`) and text-capable (`SHORT_TEXT`) complication types are both supported; frequency text only ever appears in the text-capable slot.

## Frequency-region labels

Centralized in `dsp/FrequencyBands.kt` (Rumble 20–80 Hz, Body 80–250 Hz, Warmth 250–500 Hz, Presence 2–6 kHz, Sibilance 6–10 kHz). These are listening guides only — edit the ranges in that one file if you want different boundaries.

## Demo mode

`demo/DemoSignalGenerator` produces a fixed function of elapsed time (two steady tones plus a tone that fades in/out every 20s) — deterministic, not random, and never touches the microphone. Every screen and saved snapshot/capture is labeled DEMO when active, and demo-mode readings/captures are never written to `SurfaceSummaryRepository` (the Tile/complication never show synthetic data as if it were real).

## Manual test checklist (run on a real watch; mark "not run" if unavailable)

- [ ] Fresh install → app opens on ANALYZER, "Ready" state, single Start button, "ANALYZER ›" title.
- [ ] Swipe ANALYZER↔RING; page indicator tracks correctly; edge swipe-back from the pager still
      exits/dismisses as expected; buttons don't fire from a swipe's release.
- [ ] Tap "ANALYZER ›" (or "RING ›") → opens that page's Details screen; physical/gesture back
      returns to the same page in the pager (capture keeps running, not reset).
- [ ] Analyzer → Start → permission prompt → Deny → distinct "permission denied" state (not silence).
      Allow → live RMS/radial spectrum/PK/MAX/AVG (Details) move with ambient sound; clap once →
      CLIP badge (don't do this loudly/repeatedly near the mic). Swipe to Ring and back → Analyzer is
      still running, numbers did not reset.
- [ ] Analyzer Details → Reset zeroes max/average/elapsed and clears peak hold without stopping capture.
- [ ] Background the app (crown/home) while measuring → reopen → Paused, not stale numbers shown as
      if live.
- [ ] Analyzer: tap the ring to move the selection cursor; turn the crown to move it by one band at a
      time; confirm the crown does NOT page while the dial is focused; confirm the frequency/dB
      readout always matches the highlighted band, not a different one.
- [ ] Analyzer: Freeze shows "SPECTRUM HELD" and keeps the last ring trace on screen (bars stop
      moving) while the center RMS number keeps updating live; Resume restarts the spectrum.
- [ ] Analyzer Details → Save 5 snapshots, confirm a 6th is refused; rename one, delete one; compare
      two with matching config (dashed outline draws) and mismatched config (shows "Incompatible
      comparison").
- [ ] Ring, Demo mode ON: within ~1s of a synthetic tone starting, the selected capture shows LIVE
      with a frequency; after it fades out, HELD with the same frequency; "Captures N/5 ›" reflects
      the count. Pin it from the Captures list; wait past the auto-hold window (Ring Details lets you
      set 10s for a faster check) — pinned capture must NOT be replaced even once a new candidate
      would otherwise qualify. Unpin it (independently — confirm a second pinned capture is
      unaffected), then Clear all (behind the confirm step) — hero returns to SEARCHING/DETECTING.
- [ ] Ring: play (or use Demo's) two simultaneous well-separated tones — confirm both are captured as
      distinct entries, not merged; confirm five total captures can coexist; confirm a sixth
      candidate only replaces an expired-unpinned or a sufficiently-weaker unpinned slot, and that
      "All 5 pinned" shows correctly once every slot is pinned.
- [ ] Force-stop and relaunch the app with a capture pinned beforehand → confirm it's restored,
      marked "SAVED" until re-detected, and its id doesn't collide with a newly-confirmed capture.
- [ ] Analyzer Details → Demo mode ON/OFF toggles both pages between synthetic and live mic, clearly
      labeled DEMO when on.
- [ ] Calibration (via Analyzer Details): with Demo mode OFF, walk all four steps — Prepare → Enter
      reference (adjust with crown/±, or explicitly confirm the shown example) → Measure (~3s,
      confirm it rejects a clipped or highly variable sample with a retry prompt) → Review → Save →
      the "Calibration saved" success screen → Done returns to Analyzer now showing "Estimated SPL".
      Confirm "Use dBFS" at Prepare exits without calibrating. Recalibrate and Clear calibration from
      Analyzer Details both work.
- [ ] Appearance (via Analyzer Details): switch through all 5 themes, confirm the Analyzer dial, Ring
      page, and buttons all visibly change (not just the picker itself); toggle Dim with each theme
      selected.
- [ ] Watch-face complication: with a real (non-demo) ring captured, confirm the complication shows
      the *strongest currently-detected* ring, not simply the pinned or most-recent one, if a
      different ring is momentarily louder; confirm tapping it opens Ring with that exact capture
      selected, and that a stale/cleared capture is handled gracefully (opens Ring without crashing
      or reviving old data). Confirm the Tile visibly reflects the selected theme.
- [ ] Keep-awake countdown (Analyzer Details) reaches 0 → capture stops automatically.
- [ ] At default and at enlarged system font size, confirm no primary reading is cut off, no button
      text overflows its pill, both lower-row buttons render as full circles (not clipped
      crescents), and the Analyzer center text still fits inside the ring without overlapping it, on
      your specific watch. Repeat around 192dp, 227dp, and 240dp round screens if more than one is
      available.
