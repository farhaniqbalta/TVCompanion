# Ambient Ambilight Presets Implementation Plan

> **For agentic workers.** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task by task. Check off each step after verification.

**Goal.** Expand the Ambient cycle from four looks to six with Chromatic Flow and Edge Orbit, and release Ambient's GL resources safely when the view closes.

**Architecture.** Ship two fragment-only looks using the existing vertex shader, audio snapshot, color finish, and lazy program switch. Keep GL ownership inside the renderer. Ambilight continues to sample the display through its independent MediaProjection session.

**Tech stack.** Kotlin, Android GLSurfaceView/EGL, GLSL ES 3.00, JUnit, NDK shaderc, and ADB on the Skyworth Mali-G31 TV.

**Spec.** [Ambient Ambilight Presets Worker Handoff](</Users/user/Downloads/TVCompanion_Ambient_Ambilight_Presets_Worker_Handoff.md>).

## Review

- **Baseline.** Local `main` is at `7ca23f8`, 14 commits behind the local `origin/main` ref. That ref resolves to the handoff commit `b8982f3` and contains the Ambilight modules and docs. The AirPlay runtime files are identical at both commits. The checkout also contains an untracked `.claude/` directory. Use `b8982f3` as the proposed implementation baseline in an isolated worktree, subject to the branch the user selects.
- **Sampling.** `LedPipeline` averages the entire outer 12% of each edge, roughly 11 rows on top/bottom and 19 columns on the sides at 160×90. Its letterbox check looks for more than one probed pixel above RGB channel value 24 in a row. Evaluate the final color after `finish()` and capture, including low-audio frames.
- **Corners.** The handoff's nearest-edge pseudocode projects onto the outer rectangle. A pixel pair straddling an inward corner diagonal can map to separated path positions. The plan uses aspect-correct inset rectangles whose adjacent side coordinates meet along those diagonals.
- **Teardown.** The current `releaseGl()` only deletes timer queries, and its outer guard returns immediately after a renderer failure. `queueEvent()` puts work on the GL thread. AOSP checks the event queue before a pending pause, while the EGL surface or context can already be gone. Keep the existing order and check current context ownership. [Android GLSurfaceView source](https://android.googlesource.com/platform/frameworks/base/+/master/opengl/java/android/opengl/GLSurfaceView.java#1327)
- **Preflight performed.** The host native checks and offline `:integrations:airplay-runtime:testDebugUnitTest` passed on local `main`. JDK 17 is at `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. The SDK is at `/opt/homebrew/share/android-commandlinetools`, the pinned NDK's `glslc --version` runs, and ADB sees the Skyworth. The combined AirPlay Ambient and Ambilight session still needs a live sender, MediaProjection consent, and visible strip verification.

## Global constraints

- Preserve Aurora, Strings, Slow Dusk and Ripples assets, display names, saved IDs, render behavior and order. Keep the existing `ambient` preferences file, `look` key, D-pad behavior, Back behavior and video takeover.
- Keep one full-screen draw pass, one renderer, one active program and one audio-feature read per frame. Compile the requested look on selection. The previous program may briefly coexist during link and must be released on replacement. Keep the 30 FPS target and existing 960×540 fallback.
- Use `// ambient-contract: 1`, the current shared prelude and `finish()`. Leave receiver, native DSP, capture, WLED and electrical settings outside this change. Add a vertex stage only if target-TV profiling justifies moving uniform-only math out of the fragment stage.
- Do the offline work if the TV or sender is unavailable. Report unsupported capture as a separate blocker with reproduction details and keep any fix that crosses module boundaries for separate approval.

## Review focus

- Missing or invalid shader asset, including a link failure after a valid look, uses the built-in fallback and keeps playback running (Tasks 2–3).
- Rapid switch, Back before the first frame, and failure after partial allocation release only resources owned by the current context (Tasks 3–4).
- Near-silent audio and reduced motion keep the edges colored and avoid rapid flashes or an orbit chase (Tasks 2 and 4).
- A wide ribbon remains continuous across corner diagonals, the top-left wrap, and phase rollover at both render sizes (Tasks 2 and 4).
- Display composition and physical LEDs agree while Mirroring takes over or Ambilight is stopped and restarted (Tasks 1 and 4).

### Task 1 — Confirm baseline and test existing capture (CP0)

- [ ] Record `git status -sb`, `git rev-parse HEAD origin/main` and the selected implementation base. Use an isolated worktree at the selected commit and keep local untracked work in place. Read the four handoff-listed files under `docs/ambient-lighting/` from that base.
- [ ] Confirm the dependencies in `android/local.properties`/the SDK, JDK 17 and NDK `27.2.12479018`. Run `python3 scripts/prepare-airplay-native.py` with `ANDROID_HOME` set if that worktree needs its native assets. Run `python3 scripts/check-airplay-native.py`, then from `android/` run `./gradlew :integrations:airplay-runtime:testDebugUnitTest :core:ambilight-engine:test`. Record exits and distinguish any optional shader skip.
- [ ] On the baseline TV build, start Ambilight with display capture consent, start AirPlay music and open Aurora/Ripples. Observe TV composition and LED changes together. Try look switching, Back, video takeover and Ambilight stop/restart. Record build, firmware, layout and PASS/PARTIAL/BLOCKED/NOT TESTED with evidence. If capture is black or stale, record the source, consent mode, layer symptoms and playback outcome without attributing a cause from black pixels alone.

### Task 2 — Implement and register both looks (CP1–CP3, shader tests in CP5)

**Files under `android/integrations/airplay-runtime/`.** Create `src/main/assets/ambient/chromatic_flow.frag` and `src/main/assets/ambient/edge_orbit.frag`. Modify `src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShaders.kt`, `src/main/java/id/poncol/tvcompanion/airplay/runtime/AirPlayPlaybackActivity.kt`, `src/main/res/values/strings.xml`, and `src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShadersTest.kt`.

- [ ] Update `shippedLooksCarryContractHeader` to expect exactly the original six assets and the two new fragments, with `AmbientShaders.CONTRACT` as the first line. Assert the expected six-ID list, each adjacent forward and reverse step including both wraps, and `resolve(id) == id` for all six. Extend `fragmentOnlyLookGetsDefaultVertex` to assert both new looks assemble with their own IDs, one `#version 300 es` per stage, and the default vertex shader. Keep `brokenLookFallsBack`. Run the focused test class and confirm failures for the absent IDs/assets.
- [ ] Implement Chromatic Flow with three broad anisotropic falloffs and separately drifting centers. Use existing mint/gold/teal palette entries with shader-local blending if needed. Use only periodic phase expressions, subtle RMS luminance and bass expansion, with a small beat accent. Attenuate positional motion and brightness variation under `uReduced`. Compare the four 160×90 edge bands across phases and silence. If shared vignette makes corners too dark, compensate inside this look.
- [ ] Implement Edge Orbit with top-down UV derived from `vP`, using physical coordinates `W = uAspect`, `H = 1`. Compute minimum physical edge distance `d`, then the dimensions `w = W - 2d`, `h = H - 2d`. Traverse the inset perimeter clockwise using distances `x-d`, `w+y-d`, `w+h+W-d-x`, and `2w+h+H-d-y` on top/right/bottom/left. Normalize by `2(w+h)` in the visible band and protect the denominator at the center. Apply circular band distance to `fract(4.0 * uPhase.x / TWO_PI)` and a weaker band offset by 0.4. Fade inward smoothly past the sampled zone, with a softly colored background and center haze. Under `uReduced`, hold the band positions and use small gradual luminance changes. Keep the band broad at 160×90 on all four edges, including the 19-column side sampling areas.
- [ ] Evaluate the Edge Orbit formula at 16:9 and another rectangle. At small positive `d`, sample across all four nearest-edge ties. The two limits must meet modulo one. Check top→right→bottom→left for increasing phase and equivalent band distances just before and after phase wrap. Confirm the rendered pixels with a short sequence spanning all corners and the wrap. Keep this check outside production Kotlin unless a Kotlin helper becomes necessary.
- [ ] Append `chromatic_flow` and `edge_orbit` after `ripples` in `AmbientShaders.LOOKS`. Append `R.string.airplay_runtime_ambient_look_chromatic_flow` and `R.string.airplay_runtime_ambient_look_edge_orbit` in the same order in `LOOK_NAMES`. Define the values as `Chromatic Flow` and `Edge Orbit`.
- [ ] Run `./gradlew :integrations:airplay-runtime:testDebugUnitTest --tests '*AmbientShadersTest'` from `android/`. Then set `AMBIENT_GLSLC` to the executable under the NDK `shader-tools/<host>/glslc` and rerun the same task with `--rerun`. Confirm both assembled stages of all six looks and the built-in fallback pass the compiler check, and inspect the new phase expressions in both multiplication orders. The current regex covers only one literal pattern. The offline test translates the header to ES 3.10 for shaderc. TV compilation and linking exercise the real ES 3.00 sources.
- [ ] Commit the two assets, registration and shader tests together.

### Task 3 — Harden renderer cleanup (CP4)

**Files under the runtime package.** Modify `ambient/AmbientRenderer.kt`. Change `ambient/AmbientView.kt` only if ordering evidence requires it.

- [ ] Before editing, exercise Back, quick open/close and Activity stop on the baseline debug build. Prepare an uncommitted debug-only failure after a program or buffer has been allocated to reproduce the guard skipping cleanup. Also prepare an invalid fragment body with the correct header to exercise real compile fallback.
- [ ] In `onSurfaceCreated(gl: GL10?, config: EGLConfig?)`, clear stale program, VBO, query IDs and query counters before any GL allocation or validation, and record the current EGL context as owner. Keep `useLook(name: String)` updating the requested name even when no owning context is current and defer GL work in that case.
- [ ] In `releaseGl()`, use the recorded owner and `EGL14.eglGetCurrentContext()` to decide whether explicit GL deletion is valid. Run cleanup independently of the renderer failure guard. With the owning context current, unbind and delete a nonzero program, delete the VBO and timer queries, then clear ownership and resource fields. With no matching context, clear stale references and rely on EGL teardown. Repeated release must be harmless. Make a pending draw harmless once the active program is cleared.
- [ ] Preserve `AmbientView.end()` queue-before-pause ordering and the existing Activity cleanup. Verify on TV using temporary debug logs identifying explicit deletion or EGL teardown. Cover ordinary Back, Activity stop, rapid cycle then Back, immediate close before GL initialization, context recreation, and renderer failure. Verify that a bad look uses builtin, a subsequent good look renders, and temporary shaders/programs are deleted on failure. Remove fault injection and temporary logging before committing.
- [ ] Rerun the runtime tests. Commit lifecycle hardening separately from the preset assets.

### Task 4 — Verify the combined build and collect TV evidence (CP5–CP6)

- [ ] Run the native check from root. From `android/` run `./gradlew :integrations:airplay-runtime:testDebugUnitTest :core:ambilight-engine:test :app:assembleDebug :app:lintDebug`. Rerun the shader test with `AMBIENT_GLSLC` enabled. Check the source diff for exactly the intended asset, UI, test and GL lifecycle changes.
- [ ] On TV, switch both ways through all six looks, check resource labels, reopen and verify each new selection persists. Confirm the original four looks, AirPlay audio, repeated Back/reopen, Activity stop/restart, disconnect/reconnect, and video takeover. Exercise reduced motion with the system setting and restore that setting after the run.
- [ ] With a matching track and comparable display and Ambilight settings, record an original look and both new looks with Ambilight OFF, then ON if capture works. Use the `TVCAmbient` interval/draw/GPU diagnostics, `Ambilight` Stop summary, CPU/PSS samples of the main and AirPlay processes, and direct playback observation. Record resolution, observed pacing, fallback use, CPU/PSS and audible continuity. Assess the four captured edge bands and actual LED order in Cinema and Responsive. Attempt a 20–30 minute soak and record the achieved duration.
- [ ] Prepare the reviewable commit series with a CP0–CP6 matrix (PASS/PARTIAL/BLOCKED/NOT TESTED), command results, TV footage or clearly labeled previews if available, OFF/ON performance evidence, hardware and limits. Include evidence that there is one draw pass and one active look, with no added persistent threads or changes to native DSP, capture, WLED or the four existing shader assets. Keep follow-ups separate and label any unverified hardware behavior.
