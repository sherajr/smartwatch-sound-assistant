# Measurement assumptions, limits, and manual test checklist

## Level (RMS / peak)

- Samples are normalized 16-bit PCM in roughly [-1, 1]. RMS dBFS = `10*log10(mean(x^2))`; sample peak dBFS = `20*log10(max(abs(x)))`. Both are floored at -90 dBFS (`DbScale.FLOOR_DBFS`) — silence never displays as `-Infinity`.
- Verified invariant: a sine wave with peak amplitude 0.5 reads -9.03 dBFS RMS and -6.02 dBFS sample peak (`LevelMeterTest`).
- **Session energy-average** accumulates `sum(x^2)` and a sample count across all active (non-paused) blocks, and converts to dB once when read. It is explicitly *not* an average of per-block dB readings — averaging decibels is mathematically wrong and was a specific thing this app avoids (see `LevelMeterTest."session average accumulates energy, not decibels"`).
- This is unweighted (no A/C weighting), uses unwindowed samples, and is not LUFS, true peak, LAeq, or a certified acoustic measurement. It's labeled dBFS by default.
- Digital clipping is flagged when a sample's absolute value reaches `DbScale.CLIP_THRESHOLD` (0.998). Red is reserved for this and for error states — never used to imply a hearing-safety judgement.
- Sustained true-digital-zero (not just a quiet room) surfaces as "Silence — check mic route" after ~20 consecutive near-zero blocks, since that pattern is more consistent with a muted/misrouted input than an actually quiet room.

## Calibration ("Estimated SPL")

- Calibration stores a single offset: `offsetDb = meterReadingDb - liveRawRmsDbfs`, captured while a steady, unweighted (Z) reference tone or noise plays and you read its level off a trusted SPL meter.
- The offset is applied **only** to Level's RMS-derived readings (current, max, session average), which then relabel as "Estimated SPL — reference aligned". Sample peak stays dBFS always — a single broadband offset does not make an instantaneous digital sample peak into an acoustic SPL peak.
- The offset is tied to the exact capture configuration (`CaptureConfig.fingerprint()` = sample rate + audio source) it was measured against. If the negotiated source or sample rate changes on a later Start (e.g. UNPROCESSED becomes unavailable and it falls back to MIC), the stored calibration is discarded automatically and the app reverts to plain dBFS.
- What a single offset **cannot** correct for: the microphone's actual frequency response, any gain/AGC change, overload/clipping, or anything about accuracy beyond that one steady reference level. It is not a certified measurement and dBA is never displayed.
- The Spectrum plot is always plain dBFS, never offset — calibration only affects the Level page.

## Spectrum

- Hann window (`0.5 - 0.5*cos(2*pi*n/(N-1))`), FFT size 4096, 50% overlap (new frame every 2048 samples pushed into a 4096-sample sliding window).
- One-sided amplitude normalization: raw FFT magnitude × `2/sum(window)` for all bins except DC and Nyquist (× `1/sum(window)` for those two). A full-scale, bin-aligned sine reads ~0 dBFS at its bin (`SpectrumAnalyzerTest`).
- Displayed frequency range is `0..sampleRate/2` (Nyquist) at the negotiated sample rate; resolution is `sampleRate/4096` Hz/bin. This describes the FFT's own math, not a verified microphone frequency-response bandwidth.
- Dominant-frequency detection excludes DC (bin 0) and is gated by `SpectrumAnalyzer.SILENCE_GATE_DBFS` (-60 dBFS) — quiet/noise-floor signal never produces a confident-looking frequency. Sub-bin frequency uses quadratic (parabolic) interpolation on the log-magnitude spectrum around the peak bin (tested in `SpectrumAnalyzerTest`).
- Snapshots store the full magnitude array plus sample rate, FFT size, source, and calibration state. Two snapshots (or a snapshot vs. the live spectrum) are only overlaid if `sampleRate`, `fftSize`, and calibration state all match (`SpectrumSnapshot.isComparableTo`); otherwise the UI says so instead of drawing a misleading overlay.

## Find a Ring

`dsp/RingTracker.kt` runs a four-stage pipeline (`RingTrackerSettings` centralizes every constant below):

1. **Observations** — per frame, strict local-maximum bins at least `contrastThresholdDb` (10 dB) above their neighborhood, gated by `minSignalGateDbfs` (-55 dBFS). Nearby bins from the same windowed lobe are consolidated (greedy non-max suppression) so one physical tone doesn't produce multiple observations.
2. **Tracks** (transient, not yet shown) — each observation is matched to the nearest existing track within a resolution-aware tolerance (`matchToleranceBins`), or starts a new one. A track's frequency estimate is EMA-smoothed. It survives gaps up to `maxDropoutMs` (150ms) without losing identity or resetting its confirmation clock; a gap longer than that discards it. It confirms once it has held for `confirmDurationMs` (350ms) of cumulative evidence.
3. **Captures** — on confirmation, the track's frequency is **frozen** into a `RingCapture` (the live track can keep drifting independently). If a capture already exists near that frequency (recurring tone), the existing record is updated in place instead of creating a duplicate. Silence does not erase a capture — it just stops updating `lastSeenAtMs`.
4. **Hero/selection** — the UI shows one selected capture at a time, with state SEARCHING (nothing yet) / DETECTING (an unconfirmed track exists) / LIVE (matched within the last `maxDropoutMs`) / HELD (past that but within `autoHoldMs`, default 20s, configurable 10/20/30s) / PINNED (stays selected regardless of hold expiry, until explicitly unpinned or cleared) / PAUSED (capture not running). A new confirmation only takes over the selection if the current one is unpinned *and* expired; otherwise it just joins the bounded 8-record history. History eviction never removes the pinned or currently-selected record.

All timing uses an injected `MonotonicClock` (`System.nanoTime()`-based in production), never a frame counter, so behavior is identical whether frames arrive at 10Hz or in a burst. Full behavioral contract — jitter, transient rejection, dwell timing, dropout tolerance, held-frequency freezing, auto-hold expiry/reselection, recurring-tone dedup, a louder second tone not stealing the held selection, two simultaneous resolvable tones, pin/unpin/clear/clear-all, and pause/config-change handling — is in `RingTrackerTest`, including a full-pipeline acceptance test (real FFT + synthesized PCM) for the spec's "2.15kHz for one second, then silence, then a separate 3.2kHz tone" scenario.

This is a heuristic on spectral shape and time alone. It cannot distinguish acoustic feedback from a sustained musical tone, does not identify a source, and never suggests an EQ cut — the UI always labels results "Possible ring" with that caveat visible in Ring's details screen. Haptics are not implemented at all this pass (satisfies "default off" trivially).

## Frequency-region labels

Centralized in `dsp/FrequencyBands.kt` (Rumble 20–80 Hz, Body 80–250 Hz, Warmth 250–500 Hz, Presence 2–6 kHz, Sibilance 6–10 kHz). These are listening guides only — edit the ranges in that one file if you want different boundaries.

## Demo mode

`demo/DemoSignalGenerator` produces a fixed function of elapsed time (two steady tones plus a tone that fades in/out every 20s) — deterministic, not random, and never touches the microphone. Every screen and saved snapshot is labeled DEMO when active.

## Manual test checklist (run on a real watch; mark "not run" if unavailable)

- [ ] Fresh install → app opens on LEVEL, "Ready" state, single Start button, "LEVEL ›" title.
- [ ] Swipe LEVEL→SPECTRUM→RING and back; page indicator tracks correctly; edge swipe-back from
      the pager still exits/dismisses as expected; buttons don't fire from a swipe's release.
- [ ] Tap "LEVEL ›" (or "SPECTRUM ›" / "RING ›") → opens that page's Details screen; physical/
      gesture back returns to the same page in the pager (capture keeps running, not reset).
- [ ] Level → Start → permission prompt → Deny → distinct "permission denied" state (not silence).
      Allow → live RMS/peak/PK/MAX/AVG move with ambient sound; clap once → CLIP badge (don't do
      this loudly/repeatedly near the mic). Swipe to Spectrum/Ring and back → Level is still
      running, numbers did not reset.
- [ ] Level Details → Reset zeroes max/average/elapsed without stopping capture.
- [ ] Background the app (crown/home) while measuring → reopen → Paused, not stale numbers shown
      as if live.
- [ ] Spectrum: Freeze labels "HELD" and keeps the last trace on screen (green trace stops
      moving); Resume restarts it. Move the cursor by tapping the graph and by rotating the crown.
      Confirm the crown does NOT page while the cursor is active.
- [ ] Spectrum Details → Save 5 snapshots, confirm a 6th is refused; rename one, delete one;
      compare two with matching config (dashed red overlay draws) and mismatched config (shows
      "Incompatible comparison").
- [ ] Ring, Demo mode ON: within ~1s of the synthetic ring tone starting, hero shows LIVE with a
      frequency; after it fades out, hero shows HELD and keeps the same frequency; Pin it; wait
      past the auto-hold window (Ring Details lets you set 10s for a faster check) — pinned
      capture must NOT be replaced even once a new candidate would otherwise qualify. Unpin, then
      Clear — hero returns to SEARCHING/DETECTING. Check Captures screen shows bounded history.
- [ ] Level Details → Demo mode ON/OFF toggles all three pages between synthetic and live mic,
      clearly labeled DEMO when on.
- [ ] Calibration (via Level Details): with Demo mode OFF, play a steady reference tone, dial in
      a meter reading with the crown, Confirm → Level now shows "Estimated SPL"; this clears if
      you force a different audio source/rate (not easily reproducible without specific hardware
      — note whatever is observed).
- [ ] Keep-awake countdown (Level Details) reaches 0 → capture stops automatically.
- [ ] At default and at enlarged system font size, confirm no primary reading is cut off, no
      button text overflows its pill, and both lower-row buttons render as full circles (not
      clipped crescents) on your specific watch.
