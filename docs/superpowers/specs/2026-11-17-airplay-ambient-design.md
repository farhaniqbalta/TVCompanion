# AirPlay Ambient mode — design

**Status:** Draft for user approval · **Date:** 2026-11-17 · **Branch:** `docs/airplay-visualizer-assessment`
**Source:** assessment of `TVCompanion_AirPlay_Visualizer_Implementation_Handoff.md` (2026-11-07), narrowed by the user, with visual and GPU advice from a Fable design agent.

## 1. Intent

While music plays to the TV over AirPlay, the user wants **ambient visuals that play along with the beat**: a calm, slowly moving picture that pulses in time with the song. It is mood lighting for the room, not a spectrum analyser.

It must be a strictly optional extra. **Current AirPlay behaviour stays exactly as it is.**

## 2. Goals, non-goals, anti-goals

**Goals**
- A full-screen Ambient view on the TV, opened by the user from the AirPlay playback screen, for native AirPlay audio (RAOP: PCM, ALAC, AAC).
- Visible pulses aligned with the beat as heard from the TV speakers. Calm motion between beats.
- Looks are **swappable later without code changes**. The first look is **Aurora ribbons (B)**. **Ripples (C)** ships as a second look file but is not selectable in v1.
- Runs well on the low-end TV (MT5867 / Mali-G31 / 1280×720, per the handoff). Zero analysis or GPU work when Ambient is closed.

**Non-goals (v1)**
- Look picker UI, settings toggle, palette editor, or metadata/album art.
  - *Amendment 2026-11-17: a D-pad look picker (LEFT/RIGHT while Ambient is open, choice remembered) was added by user request; see §5.7.*
- HLS / app-video audio. It plays through Android `MediaPlayer` and never reaches the native sink.
- iPhone/Mac control, AIDL changes, remote API, Bardi lamp sync, quality tiers, 60 fps.
- The handoff's benchmark campaign (`dumpsys` reports, 30-minute soaks, p95 tables). The project's docs record that CPU/RAM benchmarking is not wanted.

**Anti-goals** (the feature fails even if it technically works)
- Any change to AirPlay audio, video, session, background-listening, or reconnect behaviour.
- Audible glitches caused by the tap.
- Strobing or flashing.
- Ambient starting on its own.
- Static on-screen elements that could burn in.

## 3. Verified constraints from the code (HEAD `6d5c797`)

- **Audio path.** All native audio converges on the `writePcm` lambda in `AudioOutput::Impl::run()` (`android/integrations/uxplay-native/src/main/cpp/audio_output.cpp:108-133`).
  - Format is fixed at 44.1 kHz stereo PCM16 (`rate`/`channels` constants, line 25).
  - Samples are scaled by `gain × duck` into a local `std::vector<int16_t>`, then written to AAudio in chunks of at most 480 frames via `AAudioStream_write`.
  - Before writing, the code computes `nextStart`: the CLOCK_MONOTONIC time at which the first frame of this write will reach the output. It is derived from `AAudioStream_getTimestamp` and frames written.
- **Stream resets.** `configure`, `flush`, `end` and `focus(false)` bump `version`, and `release()` closes the stream. These are the natural "new stream" points.
- **Native lifetime.** `nativeStop(handle)` calls `destroy()`, which ends in `delete r` (`receiver_jni.cpp:295`). Any reader of the raw receiver handle after stop is a use-after-free.
- **Engine threading.** `UxPlayReceiverEngine` (implements `ReceiverEngine`) serialises native work on a single-thread `worker` executor.
- **Playback screen.** `AirPlayPlaybackActivity` runs in `:airplay` and binds `AirPlayService.ReceiverBinder`. That binder is the AIDL stub, but it also carries local-only methods such as `attachDisplay`, `detachDisplay` and `beginPlayback`.
  - The screen is a `FrameLayout`: video `SurfaceView` at the bottom, then the `waiting` ScrollView, then the `controls` toolbar.
  - `onStop()` detaches, unbinds and calls `finish()`.
  - The activity is not exported. It is reached only via TVCompanion → AirPlay settings → **Open playback →**.
- **Do not touch the video surface (critical).** `surfaceDestroyed` calls `host.detachDisplay()`, which calls `ReceiverSession.detachDisplay()` (`ReceiverSession.kt:82-89`).
  - With **Keep the current connection** off, this **stops the session**.
  - It also clears the display observer, so the screen would stop receiving state updates.
  - Therefore Ambient must never hide, resize or remove the video `SurfaceView`.
- **Native host tests.** `scripts/check-airplay-native.py` compiles `src/test/cpp/media_path_test.cpp` with the host C++17 compiler. New native tests plug in here.
- **Wire version.** `AirPlayWire.VERSION = 5` stays unchanged. Nothing crosses processes.

## 4. Approaches

| | A. Native C++ renderer (handoff) | **B. Kotlin renderer, native analysis (chosen)** |
|---|---|---|
| Drawing | GLES in the UxPlay `.so` via 4 `nativeGl*` JNI calls | `GLES30` in a `GLSurfaceView.Renderer` |
| GPU cost | Same shader, same cost | Same shader, same cost |
| CPU per frame | — | About 15 uniform calls plus 1 JNI read: tens of µs out of 33 ms |
| Failure blast radius | A GL crash kills `:airplay` and the music | A Kotlin or GL error is caught, and Ambient closes (driver crashes are not isolated in either option) |
| Coupling | Every look change rebuilds the GPL receiver `.so` | Looks are asset files; the native side holds only analysis |

- **B was chosen** by the user, on the advisor's and Fable's recommendation.
- **Canvas / normal Views were dropped.** They cannot render soft, blended light fields without banding.

**Looks considered** (previewed in a WebGL2 page with real audio):
- **A, Drifting glow field.** Fable's pick.
- **B, Aurora ribbons.** The user's pick for v1.
- **C, Ripples from the gold dot.** Kept for later.

## 5. Design

### 5.1 User flow and screen composition

- **Entry point.** On the waiting page, an **Ambient** button appears (styled with `TvUi.button`) whenever a sender is connected (`snapshot.connection != NONE`) and `!snapshot.videoActive`. It also shows while the music is paused.
- **Opening Ambient.**
  - Add a `GLSurfaceView` to the frame **above** the video view, using `setZOrderMediaOverlay(true)`.
  - Set `waiting` and `controls` to `GONE`.
  - Set `FLAG_KEEP_SCREEN_ON`.
  - The video `SurfaceView` stays attached and VISIBLE, and is never touched.
- **Back while Ambient is open.** Closes Ambient and restores the waiting page. Music continues. A second Back follows the existing behaviour.
- **Closing is the remote's job (user decision).** Pausing the song, the receiver's 1-second `AUDIO_STOPPED`, focus loss, a sender disconnect or a session replacement do **not** close Ambient. It eases into its calm idle drift, and wakes when PCM returns. If the analysis source closes, Ambient re-subscribes when a new source opens.
- **The only automatic closes:**
  - `videoActive` becomes true. Video must take over the screen unchanged.
  - The playback screen itself stops or finishes, under existing behaviour.
  - The look or EGL setup fails.
- **Activity stop.** Ambient closes first. Nothing persists, and Ambient never reopens automatically.
- **Overscan hint.** A one-line hint ("Back to close") sits inside a 5% safe margin and fades after 4 s. There is no other on-screen text.

### 5.2 Native PCM tap (inside `writePcm`)

- **Where.** After each `AAudioStream_write` returns `result > 0`, submit the `result` frames just written: `samples.data() + offset*channels`, before `offset += result`.
- **Timestamp.** Each submission carries `playoutNs = nextStart + offset * 1e9 / rate`.
- **Disabled path.** A single relaxed atomic load of an `enabled` flag, then return.
- **Enabled path.**
  - `std::mutex::try_lock` on a **preallocated mono ring** of 8192 samples (about 186 ms).
  - Downmix with `(int32_t(L) + int32_t(R)) / 2`.
  - Record the playout time of the newest sample.
  - If `try_lock` fails, drop the input and increment a counter.
- **Forbidden in the tap:** allocation, logging, JNI, blocking locks, FFT.
- **Resets.** Clear the ring and bump an analysis `generation` whenever `version` changes or the stream is released, and on `end()`, `flush()`, `focus(false)` and receiver destroy.
- **Data integrity.** The tap reads only. PCM sent to AAudio is unchanged.
- **Volume.** The tap also receives the current `gain × duck` multiplier. The analyzer divides it back out, so visuals look the same whether the iPhone volume is high or low. When the multiplier is 0 (muted), the analyzer treats the input as silence and the view goes idle. *(My call: ambient light shouldn't fade just because the music is played quietly.)*

### 5.3 Native analyzer

- **Thread.** One thread, created when the first Ambient subscription opens and joined when the last one closes or the receiver is destroyed.
- **Analysis.** Takes the newest 1024 mono samples on each hop of 512 (about 86 analyses per second), then:
  - applies a Hann window and runs a real FFT;
  - computes 16 log-spaced bands (40 Hz–16 kHz, DC excluded);
  - computes RMS and peak;
  - clamps all outputs to finite [0, 1].
- **Onset detection.**
  - Signal: low-band (bands 0–3) spectral flux.
  - Adaptive threshold: about 1.6 × the running mean over about 1 s, plus a floor.
  - Refractory gap: 280 ms.
  - Each onset records `beatCount++` and `lastBeatPlayoutNs`: the playout time of the analysed window's newest sample, not the analysis time.
- **Snapshot.** A latest-only snapshot containing `generation`, `hasRecentAudio`, `rms`, `peak`, `bands[16]`, `beatCount`, `lastBeatPlayoutNs` and `lastBeatPlayoutNs[−1..−3]`.
  - Published under a short mutex shared only by the analyzer thread and the GL reader; each side holds it for a 25-float copy. The audio thread never touches it.
  - `hasRecentAudio` goes false after 400 ms without PCM.
- **FFT.** A small internal radix-2 real FFT, covered by host tests. No new third-party dependency, so `LICENSE_COMPLIANCE.md` is unaffected. *(My call: avoids pinning and licensing KissFFT for about 80 lines of code.)*

### 5.4 Lifetime and JNI

- **Ownership.** The receiver owns a `std::shared_ptr<AnalysisSource>`, which contains the ring, analyzer and snapshot. The tap holds a raw pointer whose lifetime is bounded by the receiver.
- **`nativeOpenAmbient(receiverHandle) → subHandle`.**
  - Runs on the engine's `worker` executor.
  - Allocates a `new std::shared_ptr<AnalysisSource>(source)`.
  - Enables the tap and starts the analyzer if this is the first subscriber.
- **`nativeReadAmbient(subHandle, float[] out) → status`.**
  - Called on the GL thread once per drawn frame.
  - Copies the snapshot into a preallocated array.
  - Returns `OK`, `STALE` or `CLOSED`.
- **`nativeCloseAmbient(subHandle)`.**
  - Idempotent.
  - Stops the analyzer when the last subscriber closes.
- **Receiver destroy.** Marks the source closed, disables the tap and joins the analyzer *before* `delete r`. Open subscriptions then read `CLOSED`.
- **Java wrapper (`UxPlayAmbientSubscription`).**
  - Guards the handle with a lock shared by `read` and `close`, so a read can never race a close.
  - Opens are tagged with a request epoch, so a late open result after the screen has closed is closed immediately.

### 5.5 Runtime interfaces (no AIDL)

- **`airplay-runtime`** gains a small optional interface, implemented by `UxPlayReceiverEngine`. `ReceiverEngine` itself is unchanged:
  - `AmbientSource.openAmbient(callback)`, delivered on the main thread;
  - `AmbientSubscription.read(FloatArray): Status` and `close()`.
- **`AirPlayService.ReceiverBinder`** gains local-only `openAmbient(callback)`. This follows the existing `attachDisplay` pattern.
- No changes to `IAirPlayReceiver.aidl`, `IAirPlayEvents.aidl` or `AirPlayWire`.

### 5.6 Renderer (Kotlin, GL thread)

- **EGL setup.**
  - `setEGLContextClientVersion(3)`.
  - `setEGLConfigChooser(8, 8, 8, 0, 0, 0)`.
  - `holder.setFormat(PixelFormat.RGBX_8888)`.
  - Log `GL_RED_BITS` once in debug builds. If fewer than 8 bits, close Ambient: banding would be severe and the dither cannot fix it.
- **Pacing.** `RENDERMODE_WHEN_DIRTY`, with a `Choreographer` callback requesting a render every 2nd vsync (30 fps on 60 Hz). Use timestamps, so dt-based smoothing works at any refresh rate.
  - The callback is stopped before the view is paused or removed.
  - At most one frame is pending at a time.
- **Per frame.**
  1. One JNI read into a reused `FloatArray`.
  2. Smoothing in Kotlin: one-pole filters with attack/release time constants, `a = 1 − exp(−dt/τ)`. Constants follow Fable's table:
     - level 150/800 ms;
     - bass 60/350 ms;
     - mid 400/400 ms;
     - high 80/500 ms;
     - pulse 40/450 ms.
  3. Phase integration: `phase += dt·rate·speed`, wrapped to 2π. Rates are 0.11, 0.083, 0.047 and 0.031 rad/s. Speed is 0.6–1.3× with level, and 0.5× when idle.
  4. Uniform upload and one draw call.
- **Beat timing.**
  - `beatAges[i] = (now − lastBeatPlayoutNs[i]) − AMBIENT_SYNC_OFFSET_MS`.
  - A pulse whose age is negative has not happened yet and is ignored.
  - The pulse fires when the beat is **heard**, not when it is analysed.
  - `AMBIENT_SYNC_OFFSET_MS` is one constant, default 0, tuned by ear on the TV.
  - **If the visuals still lag the sound after tuning,** audio is **never** delayed. Instead, CP3 may add beat **prediction**: estimate the tempo from recent beat intervals and fire the predicted next beat early by the measured lag. Any beat that actually arrives re-anchors the prediction. *(My call: this keeps the anti-goal intact.)*
- **Idle.** When `hasRecentAudio` is false, everything eases over 1.5 s to intensity 0.35, speed 0.5× and no pulse.
- **Reduced motion.** Follows the system "remove animations" setting (animator duration scale = 0):
  - drift speed drops to 0.25×;
  - there is no positional beat response, only a brightness change of at most 5%;
  - the dither seed is static.
  - *(My call: no in-app toggle in v1.)*
- **Errors.** Every `GLSurfaceView.Renderer` callback body is wrapped in try/catch. Any exception, shader compile/link failure or `CLOSED` status ends with GL resources released on the GL thread and Ambient closed.
  - **Known limit.** A GPU-driver crash or a hung GL thread would still take down `:airplay` and the music. Isolating against that would need a separate process, which this design rejects. The risk exists only while Ambient is open.

### 5.7 Swappable look contract (v1)

- **Files.** Looks are assets in `airplay-runtime/src/main/assets/ambient/`: `<name>.frag`, plus an optional `<name>.vert`.
  - v1 ships `aurora.frag` and `aurora.vert`, plus three kept looks the user also liked: `ripples.frag`, `dusk.frag` (+ optional `dusk.vert`) and `strings.frag`. Ripples, Dusk and Strings are not selectable in v1. Their reference is the `ripples` and `dusk` entries in the all-looks preview (`2026-11-17-airplay-ambient-preview-all.html`) and, for Strings, the `strings` entry in `2026-11-17-airplay-ambient-preview-strings.html`.
  - The active look is a constant (`aurora`).
  - *Amendment 2026-11-17 (user request): LEFT/RIGHT on the D-pad now cycles `aurora`, `strings`, `dusk`, `ripples` while Ambient is open; the choice is remembered (default `aurora`).*
- **Header.** The first line of each file is `// ambient-contract: 1`. Mismatched files are rejected.
- **Host prelude.** The host prepends the same prelude to both stages:
  - `#version 300 es` and precision lines;
  - the uniforms listed below;
  - `hash12`;
  - `finish(col, p)`, which applies the pulse gain capped at +8%, a vignette, a soft shoulder, gamma-2 encoding and ±0.5 LSB dither.
- **Uniforms (fixed):**
  - `uRes`, `uAspect`;
  - `uPhase` (vec4, wrapped);
  - `uLevel`, `uBass`, `uMid`, `uHigh`, `uPulse`, `uReduced`;
  - `uBeatAges` (vec4, seconds; ≥ 99 means none);
  - `uBeatCount` (float, running count of heard beats, so a look can assign successive beats to different elements; mod 2^20);
  - `uBase`, `uPalette[4]` (mint, gold, teal, pale; squared on the CPU);
  - `uDitherSeed`.
- **Geometry (fixed).**
  - A 128-column × 1-row triangle strip, 258 vertices, in a real VBO with `in vec2 aPos` at location 0.
  - A host default vertex shader outputs `vP` (aspect-space position). Looks without a `.vert` use it.
- **Rule for every look.** A wrapped phase may only be multiplied by an **integer**. Variety comes from sums of different components plus constant offsets. *(Fable found that non-integer multiples cause visible jumps when a phase wraps.)*
- **Fallback.** If the selected look fails, the host logs it and falls back to a built-in minimal look. If that also fails, Ambient closes.

### 5.8 Aurora look (B), optimised

- **Vertex shader.** All math that depends only on x goes here:
  - three ribbon heights (highp), built from integer phase multiples;
  - shimmer;
  - the beat crest (travelling Gaussian with `exp(−age·1.6)` decay);
  - tints premultiplied by level, crest boost and the reduced-motion factors.
- **Varyings.** Six: `vP`, heights (vec3 highp), shimmer (vec3), and three tints (vec3).
- **Fragment shader.** Per ribbon: one rational falloff and two constant-edge smoothsteps, then `finish()`.
- **Look.** Same as the approved preview: three soft ribbons in the lower area; bass lifts them, highs add shimmer, and each heard beat sends a crest along them.
  - **Reference.** The approved preview is saved as `docs/superpowers/specs/2026-11-17-airplay-ambient-preview.html`. Its `aurora` and `ripples` entries are the visual reference for ribbon heights and widths, crest speed and width, tint mapping, shimmer frequency and palette assignment. Its JavaScript holds the band mapping, smoothing and onset logic.
  - **Not a straight copy.** Before porting, two preview defects must be fixed:
    - lines 137–138 multiply wrapped phases by non-integer factors;
    - drift runs 6× faster than the TV speed.
- **Cost (Fable estimate, unmeasured).** About 100–110 scalar operations per pixel, with no trig or exp per pixel.
- **Columns.** 128 keeps shimmer interpolation error under 1% brightness. Do not go below 128.

### 5.9 Palette and display

- **Colours.** Base `#101B21` (lifted from the brand `#0E171C` to avoid black crush). Mint `#A2E3D0`, gold `#EFD29A`, teal `#1F6F78`, pale `#F2F5F4` at low intensity.
- **Brightness.** Peak output at or below about 85% white. The beat raises luminance by at most +8% (5% in reduced motion), so there is no flashing at any tempo.

### 5.10 Performance plan

- **Resolution.** Start at full 1280×720, with no FBO.
- **Fallback.** If the debug frame-time log on the TV shows sustained misses, call `holder.setFixedSize(960, 540)`; the compositor upscales for free. Re-check after that change only.
  - *Amended 2026-11-17:* the fallback is now automatic per look: a mean interval >33.9 ms in two consecutive 3 s windows (after 1 s warm-up) switches that look to 960×540 for the process lifetime.
- **Debug-only diagnostics.** Logged once every 10 s:
  - frame interval and draw time;
  - GPU time per draw (`GL_EXT_disjoint_timer_query`, when available);
  - tap drop count;
  - analyses per second.
- No release-build logging and no benchmark reports.

## 6. Verification

- **Host C++ tests** (`check-airplay-native.py`):
  - downmix at PCM16 extremes;
  - ring overflow and drop counting;
  - disabled-path no-op;
  - partial-write offsets and playout timestamps;
  - FFT on silence, 100 Hz and 1 kHz sines (the expected band dominates) and noise;
  - RMS/peak bounds, with no NaN or Inf;
  - onset detection on a synthetic 118 BPM kick track: one onset per kick, the refractory gap respected, none on sustained tones;
  - generation reset;
  - subscription lifetime: open/close/open, double close, and receiver destroy before close.
- **Kotlin unit tests:**
  - Ambient open/close policy: opening is allowed only when a sender is connected and video is not active; pause, `AUDIO_STOPPED`, disconnect and session replacement keep it open; video starting closes it; a stale open epoch is rejected;
  - smoothing and phase-wrap maths.
- **On the TV (user confirmation, as in earlier phases):**
  - **CP1:** Ambient layering over the untouched video view with a synthetic signal. Toggle 20×. Video arriving while Ambient is open shows video. Back keeps the music playing. With "Keep the current connection" off, the session survives opening and closing Ambient.
  - **CP3:** real music pulses on the beat; the sync offset is tuned by ear; no audible glitches.
  - **Regression:** video mirroring, background listening, sender takeover, and HLS app video, all with Ambient never opened.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` and `scripts/check-airplay-native.py` pass at each checkpoint.

## 7. Checkpoints

1. **CP1 – Surface proof.** Kotlin renderer, look loader, aurora shader, Ambient button and lifecycle, driven by a **synthetic** feature generator. No native changes.
2. **CP2 – Native tap and analyzer.** Tap, ring, FFT, onset detection, snapshot and subscription JNI, with host tests. No UI change.
3. **CP3 – End to end.** Connect the real snapshot, add playout-timed beats and tune the sync offset on the TV.
4. **CP4 – Edge cases and docs.** Lifecycle matrix, reduced motion, an `ARCHITECTURE.md` note, a `VERIFICATION.md` entry, and confirmation that the ripples file loads when temporarily set as the active look.

Each checkpoint is a focused commit. Reverting the tap commit restores the original `writePcm`.

## 8. Deviations from the handoff

- **Scope.** The handoff's MVP is otherwise kept: RAOP only, `:airplay` only, no AIDL, default off, video wins, and no `RECORD_AUDIO`.
- **Renderer in Kotlin (B), not native C++.**
- **Beat detection and playout-timed sync added.** The handoff deferred beat sync; the user asked for it.
- **Analysis window and features.** A 1024/512 window, not 2048. The renderer collapses the 16 bands to bass, mid and high.
- **Looks are swappable asset files**, with a fixed contract and a column-strip vertex stage.
- **The video `SurfaceView` is never hidden.** The handoff suggested hiding or removing it, which would stop sessions.
- **FFT.** An internal FFT, not KissFFT.
- **Verification is lighter.** No benchmark campaign; a debug frame-time log plus user confirmation on the TV instead.
- **Eight checkpoints condensed to four.**

## 9. Left to the builder

- Exact class and file names.
- Seqlock vs. double-buffer snapshot, provided the reader never blocks the writer.
- Onset threshold constants, within the stated shape, tuned against the synthetic test.
- Wording and placement of strings, using `TvUi`.
- Whether the ring lives in `audio_output.cpp` or a new `ambient/` folder next to it. Prefer a new folder with a narrow header.
