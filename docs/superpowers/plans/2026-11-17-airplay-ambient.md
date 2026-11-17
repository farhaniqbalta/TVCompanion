# AirPlay Ambient Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional full-screen Ambient view to the TV's AirPlay playback screen that drifts calmly and pulses on the heard beat of native AirPlay audio, with the Aurora look active and Ripples and Dusk shipped as kept look files.

**Architecture:**
- **Audio side (native).** A read-only tap after `AAudioStream_write` feeds a bounded mono ring. A native analyzer thread, running only while Ambient is subscribed, produces a latest-only feature snapshot with playout-timed beats.
- **Drawing side (Kotlin).** A `GLSurfaceView` (ES 3.0) layered above the untouched video `SurfaceView` reads that snapshot once per frame through JNI. It smooths it and draws a swappable look loaded from assets onto a 128-column strip.

**Tech Stack:** Kotlin/Java (Android, minSdk 26, compileSdk 34), C++17 NDK 27.2.12479018 (`armeabi-v7a`), OpenGL ES 3.0 / GLSL ES 3.00, JUnit 4, host C++ asserts via `scripts/check-airplay-native.py`.

**Spec:** `docs/superpowers/specs/2026-11-17-airplay-ambient-design.md`. Visual reference: `docs/superpowers/specs/2026-11-17-airplay-ambient-preview-all.html`, the `aurora`, `ripples` and `dusk` entries plus its JS analysis.

## Global Constraints

- **Never touch the video `SurfaceView`.** Do not hide, resize or remove it. Its `surfaceDestroyed` calls `detachDisplay()`, which can stop the session (`ReceiverSession.kt:82-89`).
- **Nothing crosses processes.** No changes to `IAirPlayReceiver.aidl`, `IAirPlayEvents.aidl` or `AirPlayWire.VERSION` (stays 5).
- **The AirPlay audio path is unchanged.** PCM sent to AAudio is byte-identical. The tap never allocates, logs, calls JNI or takes a blocking lock. `try_lock` is the only lock it uses.
- **Zero work when closed.** No analyzer thread and no GL work unless an Ambient subscription is open.
- **Closing.**
  - Only the remote's Back closes Ambient.
  - Automatic closes happen only on `videoActive == true`, when the playback activity stops, or on a look/EGL failure.
  - Pause, `AUDIO_STOPPED`, disconnect and session replacement keep Ambient open in idle.
- **Opening.** The Ambient button shows when `connection != NONE && !videoActive`.
- **Pacing and colour.** 30 fps: `RENDERMODE_WHEN_DIRTY` plus `Choreographer`, rendering every 2nd vsync. EGL config `(8, 8, 8, 0, 0, 0)` with `PixelFormat.RGBX_8888`. If `GL_RED_BITS < 8`, close Ambient.
- **Look contract v1.**
  - First line of every look file: `// ambient-contract: 1`.
  - Wrapped phases may be multiplied **only by integers**.
  - Uniforms, all declared `highp` in both stages: `uRes` (vec2), `uAspect`, `uPhase` (vec4), `uLevel`, `uBass`, `uMid`, `uHigh`, `uPulse`, `uReduced`, `uBeatAges` (vec4, seconds, `>= 99` = none), `uBeatCount`, `uBase` (vec3), `uPalette[4]` (vec3: mint, gold, teal, pale), `uDitherSeed`.
- **Palette** (squared on the CPU): base `#101B21`, mint `#A2E3D0`, gold `#EFD29A`, teal `#1F6F78`, pale `#F2F5F4`.
- **Smoothing constants** (attack/release, ms):
  - level 150/800;
  - bass 60/350;
  - mid 400/400;
  - high 80/500;
  - pulse 40/450;
  - idle release 1500.
- **Drift phase rates** (rad/s): 0.11, 0.083, 0.047, 0.031. Speed 0.6–1.3× with level, 0.5× when idle, ×0.25 with reduced motion.
- **Analysis.** 44.1 kHz input. 1024-sample Hann window, hop 512. 16 log bands from 40 Hz to 16 kHz with DC excluded, band value `clamp((dB + 80) / 55)`.
- **Onset detection.** Signal is low = mean(bands 0–3). Flux = `max(0, low − prevLow)`. Threshold = `1.6 × mean(flux over last 86 analyses) + 0.02`. Also requires `low > 0.25` and at least 280 ms since the last beat, measured on playout time.
- **Native snapshot.** `hasRecentAudio` is false after 400 ms without submitted PCM. The snapshot mutex is shared only by the analyzer thread and the GL reader; the audio thread never touches it.
- **Strings.** `airplay_runtime_ambient` = "Ambient"; `airplay_runtime_ambient_hint` = "Back to close". The hint fades after 4 s and sits inside a 5% safe margin.
- **No new third-party dependencies.**
- **Commit trailer.** Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Video arrives while Ambient is open.** Ambient must close and the existing video shows, with the session unaffected. Pinned in Task 1 (`AmbientPolicyTest.videoForcesClose`) and the Task 3 device check.
2. **The receiver engine stops or restarts under an open Ambient.** Reads must return CLOSED with no use-after-free, and Ambient re-subscribes within about 1 s once a new engine exists. Pinned in Task 5 (`closeBeforeUnsubscribe`) and Task 6 (`reopenAfterClosedAtMostOncePerInterval`).
3. **Back or toggle pressed while an async open is in flight.** The late subscription must be closed immediately, never leaked or rendered. Pinned in Task 1 (`AmbientEpochTest`).
4. **Mute, volume 0 or a tiny volume.** No NaN, Inf or overflow. Mute reads as silence (idle). Low volume keeps full visuals. Pinned in Task 4 (`tapUndoesGainAndMutesToZero`).
5. **Look asset missing or broken, or a 16-bit EGL surface.** Ambient falls back to the built-in look, or closes, and AirPlay is untouched. Pinned in Task 2 (`brokenLookFallsBack`) and the Task 3 device check.

---

### Task 1: Ambient contract and pure Kotlin logic

**Files:**
- Create: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientSource.kt`
- Create: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientPolicy.kt`
- Create: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientMotion.kt`
- Test: `android/integrations/airplay-runtime/src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientPolicyTest.kt`
- Test: `android/integrations/airplay-runtime/src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientMotionTest.kt`

**Interfaces:**

Produces in `AmbientSource.kt`, package `...runtime.ambient`, with no Android imports:

```kotlin
enum class AmbientStatus { OK, STALE, CLOSED }
interface AmbientSubscription : AutoCloseable { fun read(out: FloatArray): AmbientStatus; override fun close() }
fun interface AmbientCallback { fun onOpened(subscription: AmbientSubscription?) }   // always delivered on main
interface AmbientSource { fun openAmbient(callback: AmbientCallback) }
object AmbientFrame {   // float layout filled by native read
    const val SIZE = 25; const val GENERATION = 0; const val HAS_RECENT = 1; const val RMS = 2; const val PEAK = 3
    const val BANDS = 4 /* 16 values */; const val BEAT_COUNT = 20; const val BEAT_AGE_MS = 21 /* 4 values, newest first; 99000 = none; negative = not yet heard */
}
```

Produces in `AmbientPolicy.kt`:
- `object AmbientPolicy`:
  - `fun canOpen(s: AirPlaySnapshot): Boolean` returns `s.connection != NONE && !s.videoActive`.
  - `fun mustClose(s: AirPlaySnapshot): Boolean` returns `s.videoActive`.
- `class AmbientEpoch { fun next(): Int; fun isCurrent(epoch: Int): Boolean; fun invalidate() }`.

Produces in `AmbientMotion.kt`:
- `class AmbientMotion(reduced: Boolean)` with read-only properties:
  - `level`, `bass`, `mid`, `high`, `pulse`, `beatCount`, `ditherSeed`: Float;
  - `phase: FloatArray(4)`, `beatAges: FloatArray(4)`.
- `fun update(frame: FloatArray, status: AmbientStatus, dtSec: Float, timeSec: Float)`.
- `companion object { const val SYNC_OFFSET_MS = 0f }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AmbientPolicyTest {
    private val ready = AirPlaySnapshot(phase = AirPlayPhase.READY, connection = AirPlayConnection.PLAYING, audioActive = true)
    @Test fun opensOnlyWithSenderAndNoVideo() {
        assertTrue(AmbientPolicy.canOpen(ready))
        assertTrue(AmbientPolicy.canOpen(ready.copy(audioActive = false)))          // paused still allowed
        assertFalse(AmbientPolicy.canOpen(ready.copy(connection = AirPlayConnection.NONE)))
        assertFalse(AmbientPolicy.canOpen(ready.copy(videoActive = true)))
    }
    @Test fun videoForcesClose() {
        assertTrue(AmbientPolicy.mustClose(ready.copy(videoActive = true)))
        assertFalse(AmbientPolicy.mustClose(ready.copy(audioActive = false, connection = AirPlayConnection.NONE)))
    }
}
class AmbientEpochTest {
    @Test fun staleOpenIsRejected() {
        val e = AmbientEpoch(); val first = e.next(); val second = e.next()
        assertFalse(e.isCurrent(first)); assertTrue(e.isCurrent(second))
        e.invalidate(); assertFalse(e.isCurrent(second))
    }
}
class AmbientMotionTest {
    private fun frame(rms: Float = 0f, bass: Float = 0f, recent: Boolean = true, beatAgeMs: Float = 99000f) =
        FloatArray(AmbientFrame.SIZE).also { f ->
            f[AmbientFrame.HAS_RECENT] = if (recent) 1f else 0f; f[AmbientFrame.RMS] = rms
            for (b in 0..3) f[AmbientFrame.BANDS + b] = bass
            for (i in 0..3) f[AmbientFrame.BEAT_AGE_MS + i] = 99000f
            f[AmbientFrame.BEAT_AGE_MS] = beatAgeMs
        }
    @Test fun phasesStayWrappedOverHours() {
        val m = AmbientMotion(false)
        repeat(30 * 3600 * 3) { m.update(frame(rms = 0.3f), AmbientStatus.OK, 1f / 30f, it / 30f) }
        m.phase.forEach { assertTrue(it >= 0f && it < 2f * Math.PI.toFloat()) }
    }
    @Test fun attackFasterThanRelease() {
        val m = AmbientMotion(false)
        m.update(frame(bass = 1f), AmbientStatus.OK, 0.06f, 0f); val up = m.bass
        assertEquals(1f - kotlin.math.exp(-1f), up, 0.02f)                       // one attack tau (60 ms)
        m.update(frame(bass = 0f), AmbientStatus.OK, 0.06f, 0.06f)
        assertTrue(m.bass > up * 0.8f)                                            // release tau 350 ms
    }
    @Test fun idleDecaysAndClosedIsSilent() {
        val m = AmbientMotion(false)
        repeat(30) { m.update(frame(rms = 0.3f, bass = 0.8f), AmbientStatus.OK, 1f / 30f, it / 30f) }
        repeat(150) { m.update(frame(recent = false), AmbientStatus.STALE, 1f / 30f, 1f + it / 30f) }
        assertTrue(m.level < 0.05f && m.bass < 0.05f && m.pulse < 0.05f)
        m.update(frame(rms = 1f, bass = 1f), AmbientStatus.CLOSED, 1f / 30f, 7f)
        assertTrue(m.level < 0.05f)
    }
    @Test fun unheardBeatIsIgnoredAndHeardBeatPulses() {
        val m = AmbientMotion(false)
        m.update(frame(beatAgeMs = -40f), AmbientStatus.OK, 1f / 30f, 0f)
        assertEquals(99f, m.beatAges[0], 0f)
        m.update(frame(beatAgeMs = 50f), AmbientStatus.OK, 1f / 30f, 0.033f)
        assertEquals(0.05f, m.beatAges[0], 0.001f); assertTrue(m.pulse > 0.3f)
    }
    @Test fun reducedMotionHasNoPulseAndStaticDither() {
        val m = AmbientMotion(true)
        m.update(frame(beatAgeMs = 50f, bass = 1f), AmbientStatus.OK, 1f / 30f, 0f); val seed = m.ditherSeed
        m.update(frame(beatAgeMs = 80f, bass = 1f), AmbientStatus.OK, 1f / 30f, 0.033f)
        assertEquals(0f, m.pulse, 0f); assertEquals(seed, m.ditherSeed, 0f)
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `cd android && ./gradlew :integrations:airplay-runtime:testDebugUnitTest --tests '*ambient*'`

Expected: compilation FAIL (unresolved `AmbientPolicy` and the others).

- [ ] **Step 3: Implement the three files**

`AmbientMotion.update` follows the preview's `tick()` (all-looks preview, JS section), with these rules:
- **Inputs.**
  - `level = min(1, rms × 3.2)`;
  - `bass` / `mid` / `high` = means of bands 0–3 / 4–9 / 10–15;
  - smoothing per Global Constraints, `a = 1 − exp(−dt / τ)`.
- **Idle.** Idle when `status != OK || frame[HAS_RECENT] == 0`. In idle, targets go to 0 (mid to 0.5) with a 1500 ms release, and pulse to 0.
- **Beat ages.** `beatAges[i] = (frame[BEAT_AGE_MS + i] − SYNC_OFFSET_MS) / 1000`. A negative age becomes 99.
- **Pulse.** Envelope `env = age < 0.04 ? age / 0.04 : exp(−(age − 0.04) / 0.3)`. Pulse target `max(env, 0.6 × bass)`. Pulse is forced to 0 when `reduced`.
- **Phases.** `phase[i] = (phase[i] + dt × rate[i] × speed) mod 2π`.
- **Dither seed.** `(timeSec × 61) mod 97`, or 0 when `reduced`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: same command. Expected: PASS, with 0 failures.

- [ ] **Step 5: Commit**

```bash
git add android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient android/integrations/airplay-runtime/src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient
git commit -m "Ambient: contract, open/close policy and motion smoothing"
```

---

### Task 2: Look assets and shader assembly

**Files:**
- Create: `android/integrations/airplay-runtime/src/main/assets/ambient/aurora.vert`, `aurora.frag`, `ripples.frag`, `dusk.frag`
- Create: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShaders.kt`
- Test: `android/integrations/airplay-runtime/src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShadersTest.kt`

**Interfaces:**

Produces in `AmbientShaders.kt`:

```kotlin
object AmbientShaders {
    const val CONTRACT = "// ambient-contract: 1"; const val ACTIVE_LOOK = "aurora"; const val COLUMNS = 128
    data class Sources(val vertex: String, val fragment: String, val look: String)
    /** read(name) returns file text or null. Falls back to BUILTIN when the look is missing or lacks the header. */
    fun assemble(look: String, read: (String) -> String?): Sources
    val UNIFORMS: List<String>   // exactly the Global Constraints list, used by the renderer for locations
}
```

**Prelude.**
- **Shared by both stages:** `#version 300 es`, the uniform block (all `highp`), `float sq(float)` and `hash12`.
- **Vertex stage:** `precision highp float; in vec2 aPos; out highp vec2 vP;`. The **default vertex body** is `vP = vec2(aPos.x * 0.5 * uAspect, aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0);`.
- **Fragment stage:** `precision mediump float; in highp vec2 vP; out vec4 fragColor;` plus `finish(col, p)` copied from the preview's COMMON block.
- **Look bodies.** A look's `.vert`, when present, replaces the default vertex body, and must still write `vP` and `gl_Position`.
- **Builtin fallback.** `BUILTIN` is a fragment-only look: a soft teal gradient, with `uLevel` raising its brightness.

**Porting the looks:**
- **`ripples.frag` and `dusk.frag`.** Port the bodies from the all-looks preview, replacing `aspectPos()` with `vP`. They are fragment-only.
- **`aurora.vert`.** Moves all x-only maths out of the preview's `ribbon()`:

```glsl
// ambient-contract: 1
out highp vec3 vY; out vec3 vShim; out vec3 vT0; out vec3 vT1; out vec3 vT2;
vec3 tintFor(float x, float fk, float pc, float crest, float lvl){
  vec3 t = mix(uPalette[0], uPalette[1], clamp(0.5+0.5*sin(x*0.8+pc+fk*1.3)+0.2*(uMid-0.5),0.0,1.0));
  return mix(t, uPalette[2], 0.175*fk) * (0.42-0.08*fk) * lvl * (1.0+0.4*crest);
}
float ribbonY(float x, float fk, float pa, float pb, float lift, float crest){
  return -0.18+0.11*fk+lift+0.06*sin(x*(1.1+0.3*fk)+pa+fk*1.7)+0.03*sin(x*(2.6+0.5*fk)-pb)+0.03*crest;
}
void main(){
  vP = vec2(aPos.x*0.5*uAspect, aPos.y*0.5); gl_Position = vec4(aPos,0.0,1.0);
  float x = vP.x, lvl = 0.55+0.45*uLevel, lift = (1.0-uReduced)*0.05*uBass;
  float cx = (x-(-0.6*uAspect+uBeatAges.x*1.4*uAspect))*3.0;
  float crest = (1.0-uReduced)*exp(-cx*cx)*exp(-uBeatAges.x*1.6);
  // Unrolled on purpose: no dynamic indexing of outputs/vectors (Mali driver safety).
  vY = vec3(ribbonY(x,0.0,uPhase.x,          uPhase.y,          lift,crest),
            ribbonY(x,1.0,uPhase.x+uPhase.z, 2.0*uPhase.y,      lift,crest),
            ribbonY(x,2.0,2.0*uPhase.x,      uPhase.y+uPhase.w, lift,crest));
  vShim = (1.0-uReduced)*0.25*uHigh*vec3(sin(x*38.0+9.0*uPhase.z), sin(x*38.0+9.0*uPhase.z+1.0), sin(x*38.0+9.0*uPhase.z+2.0));
  vT0 = tintFor(x,0.0,uPhase.w,          crest,lvl);
  vT1 = tintFor(x,1.0,uPhase.w+uPhase.x, crest,lvl);
  vT2 = tintFor(x,2.0,2.0*uPhase.w,      crest,lvl);
}
```

- **`aurora.frag`.**
  - Declares the matching `in` varyings.
  - Per ribbon k: `dy = vP.y − vY[k]`, `t = dy / (0.05 + 0.015k)`, `soft = 1/(1+t²) × (1 − smoothstep(−0.05, 0.35, dy))`.
  - Adds `tint_k × soft × (1 + vShim[k] × smoothstep(0, 0.05, dy))` to `uBase`.
  - Ends with `fragColor = vec4(finish(col, vP), 1.0)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AmbientShadersTest {
    private val dir = java.io.File("src/main/assets/ambient")
    private val read: (String) -> String? = { java.io.File(dir, it).takeIf { f -> f.exists() }?.readText() }
    @Test fun shippedLooksCarryContractHeader() {
        val files = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("aurora.vert", "aurora.frag", "ripples.frag", "dusk.frag", "strings.frag"), files)
        dir.listFiles()!!.forEach { assertTrue(it.name, it.readText().startsWith(AmbientShaders.CONTRACT)) }
    }
    @Test fun auroraUsesItsOwnVertexStage() {
        val s = AmbientShaders.assemble("aurora", read)
        assertEquals("aurora", s.look); assertTrue(s.vertex.contains("out highp vec3 vY"))
        assertTrue(s.vertex.startsWith("#version 300 es")); assertTrue(s.fragment.startsWith("#version 300 es"))
        AmbientShaders.UNIFORMS.forEach { assertTrue(it, s.vertex.contains(it) && s.fragment.contains(it)) }
    }
    @Test fun fragmentOnlyLookGetsDefaultVertex() {
        assertTrue(AmbientShaders.assemble("dusk", read).vertex.contains("vP = vec2(aPos.x"))
    }
    @Test fun brokenLookFallsBack() {
        assertEquals("builtin", AmbientShaders.assemble("missing", read).look)
        assertEquals("builtin", AmbientShaders.assemble("x") { "void main(){}" }.look)   // no header
    }
    @Test fun noNonIntegerPhaseMultipliers() {
        val bad = Regex("""uPhase\.[xyzw]\s*\*\s*\d*\.\d*[1-9]""")
        dir.listFiles()!!.forEach { assertNull(it.name, bad.find(it.readText())) }
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `cd android && ./gradlew :integrations:airplay-runtime:testDebugUnitTest --tests '*AmbientShadersTest*'`

Expected: FAIL (`AmbientShaders` is unresolved, or the assets are missing).

- [ ] **Step 3: Write the four assets and `AmbientShaders.kt` as specified above**

- [ ] **Step 4: Run the tests and confirm they pass**

Run: same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/integrations/airplay-runtime/src/main/assets/ambient android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShaders.kt android/integrations/airplay-runtime/src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShadersTest.kt
git commit -m "Ambient: swappable look contract with aurora, ripples and dusk assets"
```

---

### Task 3: GL view, renderer and playback-screen wiring (CP1, synthetic signal)

**Files:**
- Create: `.../runtime/ambient/AmbientRenderer.kt`, `.../runtime/ambient/AmbientView.kt`, `.../runtime/ambient/SyntheticAmbientSubscription.kt`
- Modify: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/AirPlayPlaybackActivity.kt`
- Modify: `android/integrations/airplay-runtime/src/main/res/values/strings.xml` (two strings from Global Constraints)

**Interfaces:**
- **Consumes:** Task 1 (`AmbientMotion`, `AmbientPolicy`, `AmbientEpoch`, `AmbientSubscription`, `AmbientFrame`) and Task 2 (`AmbientShaders.assemble`, `COLUMNS`, `UNIFORMS`).
- **Produces:**
  - `class AmbientView(context: Context, subscription: () -> AmbientSubscription?, onFailure: (Throwable) -> Unit) : GLSurfaceView` with `fun begin()` and `fun end()`.
  - `class SyntheticAmbientSubscription : AmbientSubscription`: a 120 BPM beat with age reported relative to `System.nanoTime()`, sine-modulated RMS and bands, always `OK`.

**`AmbientView`:**
- In its constructor:
  - `setEGLContextClientVersion(3)`;
  - `setEGLConfigChooser(8, 8, 8, 0, 0, 0)`;
  - `holder.setFormat(PixelFormat.RGBX_8888)`;
  - `setZOrderMediaOverlay(true)`;
  - `setRenderer(AmbientRenderer(...))`;
  - `renderMode = RENDERMODE_WHEN_DIRTY`;
  - focusable.
- `begin()` calls `onResume()` and posts a `Choreographer` callback that calls `requestRender()` on every 2nd frame.
- `end()` removes the frame callback **first**, then calls `onPause()`.

**`AmbientRenderer`:**
- **Every callback body** is wrapped in try/catch. On a caught error it posts `onFailure` to main.
- **`onSurfaceCreated`:**
  - If `GL_RED_BITS < 8`, throw.
  - Compile and link `AmbientShaders.assemble(ACTIVE_LOOK) { assets.open("ambient/$it")... }`.
  - Look up `UNIFORMS`.
  - Build the strip VBO: 2 × (COLUMNS + 1) vertices, `x = −1 + 2i/COLUMNS`, `y = ±1`, as a `GL_TRIANGLE_STRIP` with `aPos` at location 0.
- **`onDrawFrame`:**
  - `subscription()?.read(frame) ?: CLOSED`, then `motion.update`;
  - upload uniforms (palette squared);
  - `glDrawArrays(GL_TRIANGLE_STRIP, 0, 2 × (COLUMNS + 1))`.
- **Reduced motion** is `Settings.Global.getFloat(ANIMATOR_DURATION_SCALE, 1f) == 0f`.
- **Debug logging.** When `ApplicationInfo.FLAG_DEBUGGABLE` is set, log once (tag `TVCAmbient`) `GL_RED_BITS` and the look name. Every 10 s, log the mean and max frame interval and draw time.

**Activity changes:**
- **New fields:** `ambient: AmbientView?`, `ambientSub: AmbientSubscription?`, `ambientHint: TextView?`, `ambientButton: Button`, `epoch = AmbientEpoch()`.
- **Button.** `ambientButton = ui.button(page, getString(R.string.airplay_runtime_ambient)) { openAmbient() }`, inserted just before the existing "Leave playback" page button.
- **`render()`:**
  - `ambientButton.visibility` follows `AmbientPolicy.canOpen(state)`.
  - When `ambient != null`, keep `waiting` and `controls` `GONE`.
  - If `AmbientPolicy.mustClose(state)`, call `closeAmbient()` before the existing visibility logic.
- **`openAmbient()`** (no-op if already open):
  - `ambientSub = SyntheticAmbientSubscription()`; this line is replaced in Task 6.
  - Add an `AmbientView` at the **end** of the frame at full size.
  - Add the hint TextView with 5% margins; it fades out after 4000 ms.
  - Set `waiting` and `controls` to `GONE`, focus the view, and call `begin()`.
- **`closeAmbient()`** (idempotent):
  - `epoch.invalidate()`;
  - `end()`, then remove the view and hint;
  - `ambientSub?.close()`;
  - `render(snapshot)`, then focus `ambientButton`.
- **`onStop()`** calls `closeAmbient()` first.
- **`dispatchKeyEvent`** while Ambient is open:
  - `KEYCODE_BACK`: consume DOWN; on UP, call `closeAmbient()`.
  - D-pad and ENTER: consume.
  - All other keys, including volume, pass through.
- **Unchanged.** `FLAG_KEEP_SCREEN_ON` is already set in `onCreate`. Do not touch the video `SurfaceView`.

- [ ] **Step 1: Implement the three classes and the activity changes as specified**

- [ ] **Step 2: Build and run the unit tests**

Run: `cd android && ./gradlew :integrations:airplay-runtime:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL. Existing `ReceiverSessionTest`, `VideoPresentationPolicyTest` and the Task 1–2 tests pass.

- [ ] **Step 3: CP1 device check, by the user on the TV, results recorded in the PR**

Install the debug APK, then go to AirPlay → Open playback and send music from an iPhone.

- [ ] The Ambient button appears, and Aurora shows with synthetic pulses.
- [ ] Back returns to the page and the music keeps playing. Repeat 20×.
- [ ] With "Keep the current connection" **off**, open and close Ambient: the session survives.
- [ ] Start Screen Mirroring while Ambient is open: Ambient closes and video shows.
- [ ] Logcat `TVCAmbient` reports `GL_RED_BITS=8`. Note the frame-interval log line.

- [ ] **Step 4: Commit**

```bash
git add android/integrations/airplay-runtime
git commit -m "Ambient: GL view layered over playback with synthetic signal (CP1)"
```

---

### Task 4: Native PCM tap and FFT with host tests

**Files:**
- Create: `android/integrations/uxplay-native/src/main/cpp/ambient/ambient_tap.h`, `ambient_tap.cpp`, `fft.h`, `fft.cpp`
- Create: `android/integrations/uxplay-native/src/test/cpp/ambient_test.cpp`
- Modify: `scripts/check-airplay-native.py`

**Interfaces:**

Produces:

```cpp
namespace tvc {
class AmbientTap {                       // mono float ring, capacity 8192
public:
    explicit AmbientTap(size_t capacity = 8192);
    void setEnabled(bool on) noexcept;  bool enabled() const noexcept;
    // Audio thread. Interleaved stereo PCM16 already scaled by `multiplier`; stores (L+R)/2/32768/multiplier clamped to [-1,1], 0 when multiplier<=0.
    void submit(const int16_t *stereo, int frames, float multiplier, int64_t firstFramePlayoutNs, int sampleRate) noexcept;
    // Analyzer thread. Copies newest `count` samples oldest-first; false if fewer than `count` written since reset.
    bool latest(float *out, size_t count, int64_t &newestPlayoutNs, uint64_t &totalWritten);
    void reset() noexcept;               // clears, bumps generation
    uint32_t generation() const noexcept; uint64_t dropped() const noexcept; int64_t lastSubmitSteadyNs() const noexcept;
};
// In-place radix-2 FFT of n real samples (n power of 2); writes n/2+1 magnitudes.
void realFftMagnitudes(const float *in, int n, float *magnitudes);
}
```

**Producer rules.**
- `submit` returns immediately when disabled, using a relaxed atomic load.
- Otherwise it calls `std::mutex::try_lock`. On failure it increments `dropped`.
- No allocation in `submit`. The ring is preallocated in the constructor.
- The downmix uses `int32_t` intermediates.

- [ ] **Step 1: Write the failing host test**

`ambient_test.cpp` has a single `main()` of asserts, in the same style as `media_path_test.cpp`:

```cpp
// tapExtremesNoOverflow
AmbientTap tap(8192); tap.setEnabled(true);
int16_t ext[] = {32767, 32767, -32768, -32768, 32767, -32768};
tap.submit(ext, 3, 1.0f, 1000, 44100);
float out[3]; int64_t newest; uint64_t total;
assert(tap.latest(out, 3, newest, total) && total == 3);
assert(out[0] > 0.99f && out[1] < -0.99f && std::fabs(out[2]) < 0.001f);
assert(newest == 1000 + 2 * 1000000000LL / 44100);
// tapUndoesGainAndMutesToZero
int16_t half[] = {8192, 8192}; tap.reset(); tap.submit(half, 1, 0.5f, 0, 44100);
tap.latest(out, 1, newest, total); assert(std::fabs(out[0] - 0.5f) < 0.001f);
tap.reset(); tap.submit(half, 1, 0.0f, 0, 44100); tap.latest(out, 1, newest, total); assert(out[0] == 0.0f);
tap.reset(); tap.submit(half, 1, 1e-6f, 0, 44100); tap.latest(out, 1, newest, total); assert(std::isfinite(out[0]) && out[0] <= 1.0f);
// disabledIsNoOp and resetBumpsGeneration
tap.setEnabled(false); tap.reset(); uint32_t g = tap.generation(); tap.submit(half, 1, 1.0f, 0, 44100);
assert(!tap.latest(out, 1, newest, total)); tap.reset(); assert(tap.generation() == g + 1);
// overflowKeepsNewest: write 10000 frames of ramp, newest sample equals last written
// contentionDrops: hold no lock API needed — submit from 2 threads x 100k calls; assert dropped()+accepted == calls (accepted via totalWritten)
// fftSine: 1024-sample 1 kHz sine amplitude 0.5 → argmax bin == round(1000*1024/44100) == 23
std::vector<float> s(1024), mags(513);
for (int i = 0; i < 1024; ++i) s[i] = 0.5f * std::sin(2 * M_PI * 1000.0 * i / 44100.0);
realFftMagnitudes(s.data(), 1024, mags.data());
assert(std::max_element(mags.begin() + 1, mags.end()) - mags.begin() == 23);
std::cout << "ambient checks passed\n";
```

The two outlined cases, `overflowKeepsNewest` and `contentionDrops`, must also be written as real asserts.

- [ ] **Step 2: Extend `scripts/check-airplay-native.py` and confirm it fails**

- Before the existing ALAC `codec.exists()` check, compile and run the ambient test:
  - command: `$CXX -std=c++17 -pthread -I<module>/src/main/cpp <module>/src/test/cpp/ambient_test.cpp <module>/src/main/cpp/ambient/*.cpp -o <tmp>/ambient-check`;
  - run with `check=True`.
- Update the module docstring to mention it.

Run: `python3 scripts/check-airplay-native.py`

Expected: compile FAIL (missing `ambient/ambient_tap.h`).

- [ ] **Step 3: Implement `ambient_tap.*` and `fft.*`**

The FFT is an iterative radix-2 real FFT using a precomputed bit reversal per call. No twiddle cache is needed at n = 1024.

- [ ] **Step 4: Run the script and confirm it passes**

Run: `python3 scripts/check-airplay-native.py`

Expected: prints `ambient checks passed`, then the existing media checks pass, with exit code 0.

- [ ] **Step 5: Commit**

```bash
git add android/integrations/uxplay-native/src/main/cpp/ambient android/integrations/uxplay-native/src/test/cpp/ambient_test.cpp scripts/check-airplay-native.py
git commit -m "Ambient native: bounded PCM tap and FFT with host checks"
```

---

### Task 5: Feature extractor, onset detection and AmbientSource lifetime

**Files:**
- Create: `android/integrations/uxplay-native/src/main/cpp/ambient/feature_extractor.h`, `feature_extractor.cpp`, `ambient_source.h`, `ambient_source.cpp`
- Modify: `android/integrations/uxplay-native/src/test/cpp/ambient_test.cpp`

**Interfaces:**
- **Consumes:** Task 4 (`AmbientTap`, `realFftMagnitudes`).
- **Produces:**

```cpp
namespace tvc {
struct AmbientFeatures { float rms = 0, peak = 0, bands[16] = {}; uint32_t beatCount = 0; int64_t beatPlayoutNs[4] = {0,0,0,0}; };
class FeatureExtractor {               // pure; constants from Global Constraints
public:
    void reset();
    void process(const float *window1024, int64_t newestPlayoutNs, AmbientFeatures &features);  // updates in place
};
class AmbientSource {
public:
    AmbientTap &tap();
    bool subscribe();      // false once closed; first subscriber enables tap + starts analyzer thread
    void unsubscribe();    // last subscriber stops/joins thread, disables tap
    void close();          // idempotent: closed=true, disable tap, join thread
    // Fills AmbientFrame layout (25 floats). Returns 0 OK, 1 STALE (no PCM for 400 ms), 2 CLOSED.
    int read(float *out, int64_t nowMonotonicNs);
};
}
```

**Extractor rules.**
- **Normalisation.** `mag_k = |X_k| × 2 / Σhann`.
- **Bands.** Band b covers bins `[floor(f0/df), ceil(f1/df))` with `f0 = 40·400^(b/16)`, `f1 = 40·400^((b+1)/16)` and `df = 44100/1024`. The bin index is at least 1, and each band has at least one bin.
- **Outputs.** RMS and peak come from the raw window. Onset detection follows Global Constraints.
- **Beat history.** On an onset, shift `beatPlayoutNs` newest-first, store `newestPlayoutNs` and increment `beatCount`.

**Analyzer thread.**
- Wait up to 5 ms on a condition variable with a stop flag.
- When `totalWritten` has advanced by at least 512 since the last analysis, call `latest(1024)`, run the extractor, and copy the result into the snapshot under `snapshotMutex`.
- On a tap generation change, call `extractor.reset()` and clear the snapshot.

**`read()`.**
- Copy the snapshot under `snapshotMutex`.
- Each `BEAT_AGE_MS` entry is `(now − beatPlayoutNs[i]) / 1e6`, or 99000 when the slot is 0.
- `HAS_RECENT` is `now − tap.lastSubmitSteadyNs() < 400 ms`.
- `GENERATION` and `BEAT_COUNT` are stored as `float(value % 1048576)`.

- [ ] **Step 1: Add the failing tests to `ambient_test.cpp`**

```cpp
// silenceIsZero
FeatureExtractor fx; AmbientFeatures f; std::vector<float> w(1024, 0.0f);
fx.process(w.data(), 0, f); assert(f.rms == 0 && f.beatCount == 0); for (float b : f.bands) assert(b == 0 && std::isfinite(b));
// sineHitsExpectedBand: 100 Hz -> band containing 100 Hz is argmax; 1 kHz likewise
// onsetsOnKicks: synthesize 8 s of 118 BPM kicks (140→45 Hz sweep, 350 ms exp decay) at 44.1 kHz,
// feed hop-512 windows with playout = sampleIndex*1e9/44100; assert 15 <= f.beatCount <= 17
// noOnsetOnSustainedTone: 8 s of 100 Hz sine at 0.5 -> beatCount <= 1
// refractory: two kicks 150 ms apart count once
// closeBeforeUnsubscribe
{ AmbientSource src; assert(src.subscribe()); src.close(); float out[25];
  assert(src.read(out, 0) == 2); src.unsubscribe(); assert(!src.subscribe()); }
// openCloseOpen and double close are safe; analyzer thread not running after last unsubscribe (subscribe/unsubscribe x50 under 2 s)
// endToEnd: subscribe, submit kick track through tap in 480-frame chunks with real playout times, sleep until analyzed,
// read(now) -> status 0, BEAT_COUNT > 0, bands finite
```

Write each commented case as asserts.

- [ ] **Step 2: Run and confirm failure**

Run: `python3 scripts/check-airplay-native.py`

Expected: compile FAIL (missing `feature_extractor.h`).

- [ ] **Step 3: Implement the extractor and `AmbientSource` per the rules above**

- [ ] **Step 4: Run and confirm it passes**

Run: `python3 scripts/check-airplay-native.py`

Expected: `ambient checks passed`, exit code 0.

- [ ] **Step 5: Commit**

```bash
git add android/integrations/uxplay-native/src/main/cpp/ambient android/integrations/uxplay-native/src/test/cpp/ambient_test.cpp
git commit -m "Ambient native: features, playout-timed onsets and subscription lifetime"
```

---

### Task 6: Wire the tap into the receiver and connect the real source (CP2–CP3)

**Files:**
- Modify: `android/integrations/uxplay-native/src/main/cpp/audio_output.h`, `audio_output.cpp:108-133` (tap) and the run-loop `version != current` branch and the `release` lambda (reset)
- Modify: `android/integrations/uxplay-native/src/main/cpp/receiver_jni.cpp` (`Receiver`, `nativeStart`, `destroy`)
- Modify: `android/integrations/uxplay-native/src/main/cpp/CMakeLists.txt:42`
- Create: `android/integrations/uxplay-native/src/main/cpp/ambient_jni.cpp`, `src/main/cpp/receiver_ambient.h`
- Create: `android/integrations/uxplay-native/src/main/java/id/poncol/tvcompanion/uxplay/UxPlayAmbientSubscription.java`
- Modify: `android/integrations/uxplay-native/src/main/java/id/poncol/tvcompanion/uxplay/UxPlayReceiverEngine.java`
- Modify: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/AirPlayService.kt` (`ReceiverBinder`)
- Modify: `AirPlayPlaybackActivity.kt`
- Delete: `.../runtime/ambient/SyntheticAmbientSubscription.kt`
- Test: `android/integrations/airplay-runtime/src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientReopenTest.kt`

**Interfaces:**
- **Consumes:** Tasks 1, 3 and 5.
- **Produces:**
  - `AudioOutput::setAmbientTap(AmbientTap *tap)`, stored as `std::atomic<AmbientTap*>` in `Impl`.
  - `std::shared_ptr<tvc::AmbientSource> tvc::receiverAmbient(jlong handle)`, declared in `receiver_ambient.h` and defined in `receiver_jni.cpp`.
  - The JNI statics on `UxPlayAmbientSubscription`: `nativeOpen(long receiverHandle): long`, `nativeRead(long sub, float[] out): int`, `nativeClose(long sub)`.
  - `fun ReceiverBinder.openAmbient(callback: AmbientCallback)`.
  - `internal class AmbientReopen(private val intervalMs: Long = 1000) { fun shouldReopen(status: AmbientStatus, nowMs: Long): Boolean; fun opened() }`.

**Native wiring.**
- **In `writePcm`.** After `int result = AAudioStream_write(...)` and the `result < 0` / `== 0` checks, and **before** `offset += result`, add:

  `if (auto *t = tap.load(std::memory_order_relaxed)) t->submit(samples.data() + offset * channels, result, multiplier, nextStart + int64_t(offset) * 1000000000 / rate, rate);`

- **Resets.** Call `tap->reset()` in the `release` lambda and when `version != current`.
- **`Receiver`** gains `std::shared_ptr<tvc::AmbientSource> ambient = std::make_shared<tvc::AmbientSource>()`.
- **`nativeStart`** calls `r->audio->setAmbientTap(&r->ambient->tap())` right after creating the audio output.
- **`destroy`** calls `r->ambient->close()` right after `r->audio.reset()`.
- **`ambient_jni.cpp`:**
  - `nativeOpen` returns `new std::shared_ptr<AmbientSource>(src)` cast to `jlong`, or 0 when the source is null or `!subscribe()`.
  - `nativeRead` uses a stack `float[25]`, `clock_gettime(CLOCK_MONOTONIC)` and `SetFloatArrayRegion`.
  - `nativeClose` calls `unsubscribe()` and then `delete`.
- **CMake.** Append `ambient_jni.cpp ambient/ambient_tap.cpp ambient/fft.cpp ambient/feature_extractor.cpp ambient/ambient_source.cpp` to `tvcompanion_uxplay`.

**Java and Kotlin wiring.**
- **`UxPlayAmbientSubscription`** implements `AmbientSubscription`.
  - `read` and `close` are `synchronized` on the instance.
  - After `close`, `read` returns `CLOSED` and `nativeClose` runs once.
  - Status codes map `0/1/2` to `OK/STALE/CLOSED`.
- **`UxPlayReceiverEngine`** implements `AmbientSource`. `openAmbient` posts to `worker`:
  - `long sub = (!closed && handle != 0) ? nativeOpen(handle) : 0`;
  - then `main.post(() -> callback.onOpened(sub == 0 ? null : new UxPlayAmbientSubscription(sub)))`.
  - If `worker.execute` throws `RejectedExecutionException`, or the engine is already closed, it delivers `null` on main.
- **`ReceiverBinder.openAmbient`** runs `(engine as? AmbientSource)?.openAmbient(callback) ?: main.post { callback.onOpened(null) }`.

**Activity.**
- `openAmbient()` takes `val e = epoch.next()` and calls `host?.openAmbient { sub -> if (!epoch.isCurrent(e) || ambient == null) sub?.close() else ambientSub = sub }`.
- The view's subscription lambda returns `ambientSub`. While it is null the renderer uses `CLOSED`, so the view shows idle.
- **Reopen.** The renderer posts each frame's status to main, throttled to once per second. When `AmbientReopen.shouldReopen(status, now)` returns true, the activity closes the old subscription and requests a new one with a fresh epoch.

- [ ] **Step 1: Write the failing Kotlin test**

```kotlin
class AmbientReopenTest {
    @Test fun reopenAfterClosedAtMostOncePerInterval() {
        val r = AmbientReopen(1000)
        assertFalse(r.shouldReopen(AmbientStatus.OK, 0)); assertFalse(r.shouldReopen(AmbientStatus.STALE, 0))
        assertTrue(r.shouldReopen(AmbientStatus.CLOSED, 0)); r.opened()
        assertFalse(r.shouldReopen(AmbientStatus.CLOSED, 500)); assertTrue(r.shouldReopen(AmbientStatus.CLOSED, 1001))
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `cd android && ./gradlew :integrations:airplay-runtime:testDebugUnitTest --tests '*AmbientReopenTest*'`

Expected: FAIL (`AmbientReopen` is unresolved).

- [ ] **Step 3: Implement the native, Java and Kotlin wiring above, and delete the synthetic subscription**

- [ ] **Step 4: Run all automated checks**

Run: `python3 scripts/check-airplay-native.py && cd android && ./gradlew :integrations:airplay-runtime:testDebugUnitTest :integrations:uxplay-native:testDebugUnitTest :app:assembleDebug`

Expected: `ambient checks passed`, the media checks pass, and BUILD SUCCESSFUL.

- [ ] **Step 5: CP3 device check, by the user on the TV, results recorded in the PR**

- [ ] Music plays and the ribbons pulse on the beat. Tune `AmbientMotion.SYNC_OFFSET_MS` by ear if needed, and record the final value.
- [ ] There are no audible glitches with Ambient open or closed.
- [ ] Pause on the iPhone: Ambient dims and stays open. Resume: it wakes.
- [ ] Turn iPhone volume to low: the visuals stay lively. Mute: idle.
- [ ] Use **Stop AirPlay** from the dashboard with Ambient open: no crash. Start again: Ambient wakes within about 1 s.
- [ ] Sender takeover by a second device: Ambient stays open with no stale pulses.
- [ ] Debug `TVCAmbient` log shows `dropped == 0` in normal playback. If frame intervals are missed, apply the spec §5.10 fallback (`holder.setFixedSize(960, 540)`) and re-check.

- [ ] **Step 6: Commit**

```bash
git add -A android/integrations scripts
git commit -m "Ambient: connect native audio features to the TV view (CP2-CP3)"
```

---

### Task 7: Documentation and regression verification (CP4)

**Files:**
- Modify: `docs/ARCHITECTURE.md` (an "Ambient mode" subsection on the optional audio path, thread ownership and the look contract)
- Modify: `docs/VERIFICATION.md` (a dated entry: commands run and their results, the CP1/CP3 user confirmations, the final sync offset, the 720p/540p decision)

- [ ] **Step 1: Verify the kept looks load, temporarily, without committing**

Set `AmbientShaders.ACTIVE_LOOK = "ripples"`, build, check on the TV, then do the same for `"dusk"` and `"strings"`. Revert to `"aurora"`.

Expected: both render, and logcat shows no fallback to `builtin`.

- [ ] **Step 2: Regression on the TV with Ambient never opened**

Check Screen Mirroring video, background listening, sender takeover and HLS app video. Expected: the same behaviour as before this branch.

- [ ] **Step 3: Update the two docs**

- [ ] **Step 4: Run the full checks**

Run: `python3 scripts/check-airplay-native.py && cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :integrations:airplay-runtime:testDebugUnitTest :integrations:uxplay-native:testDebugUnitTest`

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "Ambient: architecture and verification notes (CP4)"
```
