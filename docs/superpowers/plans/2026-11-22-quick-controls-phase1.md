# Quick Controls — Phase 0–1 (headless entry points) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Quick Controls operation (light power/preset, Ambilight start/stop, Ambient open/close, AirPlay status/availability, notifications on/off) becomes callable from non-UI code. Existing TV screens move onto the same entry points with no behaviour change.

**Architecture:** No new Gradle modules or processes. The light gets one process-wide `LightController` owned by `TVCompanionApplication`. Ambilight gets a public `Ambilight` facade plus a transparent `ConsentActivity`. Ambient gets an intent shortcut into `AirPlayPlaybackActivity` and an `ambientShowing` bit on the existing AIDL snapshot. `AndroidAirPlayControl` becomes public with an opt-out of auto-create. Notifications already has `AndroidNotificationRuntime` and needs nothing.

**Tech Stack:** Kotlin, Android SDK (minSdk 26, targetSdk 34), JUnit4 JVM tests, Gradle wrapper in `android/`.

**Spec:** `docs/superpowers/specs/2026-11-22-quick-controls-design.md` (Phase 0 and Phase 1). Phase 2 (gateway) and Phase 3 (iOS) get their own plans **after** the owner's device checks, since those results (KeyStore TLS, background activity starts) can change them.

## Global Constraints

- Branch `feature/quick-controls`. Never `adb install` or deploy to the TV. Build and JVM-test only.
- No new Gradle modules, processes, dependencies, polling or permanent workers.
- No feature→feature Gradle dependency. Only `:app` composes features.
- Preserve the `:airplay` process boundary and all existing user settings and startup preferences.
- No optimistic state: every outcome reflects a confirmed read, or says unconfirmed.
- No logging of addresses, keys or device identifiers.
- Every command is an explicit state (`Power(on)`, preset by name). No toggles in new APIs.
- Run all commands from `android/` unless stated: `./gradlew test`, `./gradlew assembleDebug`.

## Review Focus

1. A remote light command arrives before anyone has refreshed the light (`state.light == null`). It must read then write in one job and complete once (Task 2 test `readThenExecuteWorksWithoutPriorRefresh`).
2. The TV screen and a second caller hit the light at the same moment. The second gets `BUSY` and `done` fires exactly once per call (Task 2 test `concurrentCallerGetsBusyAndEachDoneFiresOnce`).
3. The user leaves the Light screen while a remote light operation is in flight. Closing the screen's observer must not cancel the operation or close the shared controller (Task 3 test `closingObserverDoesNotCloseControllerOrDropDone`).
4. Ambilight start requested while not set up, or already running. It must return `NOT_SET_UP` / `BUSY` without launching the consent screen (Task 4 test `startDecisionRejectsUnconfiguredAndRunning`).
5. Ambient open with an unknown look, or a close with no Ambient showing. Unknown looks are rejected, not silently replaced. A close never launches an empty playback screen (Task 5 tests `unknownLookIsRejected`, `closeRequiresShowing`).

---

### Task 0: Baseline

**Files:** none.

- [ ] **Step 1:** Run `./gradlew test` (in `android/`). Expected: `BUILD SUCCESSFUL`. Record any pre-existing failures verbatim in the task report. Do not fix them in this plan.
- [ ] **Step 2:** Run `swift test` in `ios/TVCompanionKit` and in `ios/TVCompanionAudio`. Expected: all tests pass. Record the results.
- [ ] **Step 3:** Run `./gradlew assembleDebug`. Expected: `BUILD SUCCESSFUL`. Do not install the APK.

---

### Task 1: `LightPresets` in core:domain

**Files:**
- Create: `android/core/domain/src/main/java/id/poncol/tvcompanion/domain/LightPresets.kt`
- Create test: `android/core/domain/src/test/java/id/poncol/tvcompanion/domain/LightPresetsTest.kt`
- Modify: `android/features/smart-home/src/main/java/id/poncol/tvcompanion/smarthome/ColorControls.kt` (`base` delegates)
- Modify: `android/features/smart-home/src/main/java/id/poncol/tvcompanion/smarthome/LightScreen.kt:72-74` (preset list comes from `LightPresets.HUES`; string labels stay in smart-home, mapped by name)

**Interfaces — Produces:**
```kotlin
object LightPresets {
    /** Preset name → hue, in display order. */
    val HUES: Map<String, Int> // linkedMapOf("red" to 0, "orange" to 30, "yellow" to 60, "green" to 120, "blue" to 240, "purple" to 280)
    const val WHITE = "white"
    val NAMES: List<String>      // HUES.keys + WHITE
    fun base(state: LightState): LightColor?            // moved verbatim from ColorControls.base
    fun command(state: LightState, name: String): LightCommand?  // null = unsupported now (off, no colour) or unknown name
}
```

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun colourPresetKeepsConfirmedBrightnessAndSetsFullSaturation() {
    val s = LightState(on = true, whiteMode = true, brightness = 40, color = LightColor(10, 200, 900))
    assertEquals(LightCommand.Color(LightColor(240, 1000, 400)), LightPresets.command(s, "blue"))
}
@Test fun whitePresetRequiresWhiteSupport() {
    val s = LightState(on = true, whiteMode = false, brightness = 40, color = LightColor(0, 1000, 1000), colorMode = true)
    assertEquals(LightCommand.WhiteMode, LightPresets.command(s, "white"))
    assertNull(LightPresets.command(s.copy(on = false), "white"))
}
@Test fun offOrUnknownPresetIsUnsupported() {
    val s = LightState(on = false, whiteMode = true, brightness = 40, color = LightColor(0, 0, 400))
    assertNull(LightPresets.command(s, "blue"))
    assertNull(LightPresets.command(s.copy(on = true), "teal"))
}
@Test fun namesAreDisplayOrder() = assertEquals(listOf("red","orange","yellow","green","blue","purple","white"), LightPresets.NAMES)
```
- [ ] **Step 2:** `./gradlew :core:domain:test --tests '*LightPresetsTest'`. Expected: FAIL (unresolved `LightPresets`).
- [ ] **Step 3:** Implement `LightPresets`. Make `ColorControls.base(state) = LightPresets.base(state)`. In `LightScreen`, build preset buttons from `LightPresets.HUES` with a `name → R.string.light_*` map, and use `LightPresets.command` for the click.
- [ ] **Step 4:** `./gradlew :core:domain:test :features:smart-home:test`. Expected: PASS, including the existing `ColorControlsTest`.
- [ ] **Step 5:** Commit `Move light presets into core domain`.

---

### Task 2: `LightController` — shared, multi-observer, completions, read-then-write

**Files:**
- Modify: `android/core/domain/src/main/java/id/poncol/tvcompanion/application/LightController.kt`
- Modify test: `android/core/domain/src/test/java/id/poncol/tvcompanion/application/LightControllerTest.kt`

**Interfaces — Produces:**
```kotlin
enum class LightOutcome { CONFIRMED, BUSY, UNSUPPORTED, UNAVAILABLE, UNCONFIRMED, CLOSED }
data class LightResult(val outcome: LightOutcome, val state: LightController.State)

class LightController(...) {                          // constructor unchanged
    fun observe(observer: (State) -> Unit): AutoCloseable   // was Unit; many observers; immediate callback with current state
    fun refresh(done: ((LightResult) -> Unit)? = null)
    fun execute(command: LightCommand, done: ((LightResult) -> Unit)? = null)   // UI path, uses current state checks as today
    /** Remote path: one worker job reads, builds the command from the fresh state, writes. */
    fun readThenExecute(build: (LightState) -> LightCommand?, done: (LightResult) -> Unit)
    override fun close()                             // unchanged semantics; only process death calls it now
}
```
Rules: `done` is invoked exactly once, on the `dispatch` thread, for every call:
- `BUSY` when an operation is already running.
- `UNSUPPORTED` when the command is rejected by the current-state checks, `build` returns null, or (UI path) `state.light == null`.
- `UNAVAILABLE` when a read fails.
- `UNCONFIRMED` when a write fails.
- `CLOSED` after close or a rejected executor.

Keep the existing English `message` strings in `State`.

- [ ] **Step 1: Write the failing tests** (reuse the file's `Worker`/`Lamp` fakes)
```kotlin
@Test fun readThenExecuteWorksWithoutPriorRefresh() {
    val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
    var result: LightResult? = null
    c.readThenExecute({ LightCommand.Power(false) }) { result = it }
    worker.complete()
    assertEquals(LightOutcome.CONFIRMED, result!!.outcome)
    assertEquals(false, result!!.state.light?.on); assertEquals(1, lamp.reads); assertEquals(1, lamp.writes)
}
@Test fun readThenExecuteBuildNullIsUnsupportedWithoutWrite() {
    val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
    var result: LightResult? = null
    c.readThenExecute({ null }) { result = it }; worker.complete()
    assertEquals(LightOutcome.UNSUPPORTED, result!!.outcome); assertEquals(0, lamp.writes)
    assertNotNull(c.state.light) // fresh read still published
}
@Test fun concurrentCallerGetsBusyAndEachDoneFiresOnce() {
    val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
    val seen = mutableListOf<LightOutcome>()
    c.refresh { seen += it.outcome }
    c.readThenExecute({ LightCommand.Power(false) }) { seen += it.outcome }
    assertEquals(listOf(LightOutcome.BUSY), seen)
    worker.complete()
    assertEquals(listOf(LightOutcome.BUSY, LightOutcome.CONFIRMED), seen)
    assertTrue(worker.tasks.isEmpty())
}
@Test fun failedWriteIsUnconfirmedAndUiExecuteWithoutStateIsUnsupported() {
    val worker = Worker(); val lamp = Lamp().apply { failWrite = true }; val c = LightController(lamp, { it() }, worker)
    var ui: LightResult? = null; c.execute(LightCommand.Power(true)) { ui = it }
    assertEquals(LightOutcome.UNSUPPORTED, ui!!.outcome)
    var remote: LightResult? = null; c.readThenExecute({ LightCommand.Power(true) }) { remote = it }; worker.complete()
    assertEquals(LightOutcome.UNCONFIRMED, remote!!.outcome); assertEquals(1, lamp.writes) // no retry
}
@Test fun multipleObserversAndCloseHandle() {
    val worker = Worker(); val c = LightController(Lamp(), { it() }, worker)
    var a = 0; var b = 0
    val ha = c.observe { a++ }; c.observe { b++ }
    ha.close(); c.refresh(); worker.complete()
    assertEquals(1, a); assertEquals(3, b) // initial + busy + result
}
@Test fun afterCloseEveryCallCompletesClosed() {
    val c = LightController(Lamp(), { it() }, Worker()); c.close()
    var r: LightResult? = null; c.readThenExecute({ LightCommand.Power(true) }) { r = it }
    assertEquals(LightOutcome.CLOSED, r!!.outcome)
}
```
Update the existing test `closeDropsPendingUiResultsAndReleasesObserver` to the new `observe` signature only. Its assertions stay.
- [ ] **Step 2:** `./gradlew :core:domain:test --tests '*LightControllerTest'`. Expected: FAIL (compile errors on new API).
- [ ] **Step 3:** Implement. Observers become a `LinkedHashSet` touched only on the dispatch thread. `readThenExecute` runs `gateway.read()` then `build(it)?.let(gateway::execute)` inside one `worker.execute`.
- [ ] **Step 4:** `./gradlew :core:domain:test`. Expected: PASS (all 7 old + 6 new).
- [ ] **Step 5:** Commit `Let one LightController serve several callers with explicit outcomes`.

---

### Task 3: App owns the light controller; screen only observes

**Files:**
- Modify: `android/app/src/main/java/id/poncol/tvcompanion/app/TVCompanionApplication.kt` (add `val light: LightController by lazy { LightController(TuyaLightGateway(this), { task -> mainHandler.post(task) }) }`)
- Modify: `android/app/src/main/java/id/poncol/tvcompanion/app/MainActivity.kt` (`showLight` uses `(application as TVCompanionApplication).light`; `releaseLight` closes only the screen's observer handle, never the controller)
- Modify: `android/features/smart-home/src/main/java/id/poncol/tvcompanion/smarthome/LightScreen.kt:116` (keep the `AutoCloseable` from `observe`, close it in `onDetachedFromWindow`)
- Test: `LightControllerTest.kt` (one more test, below)

**Interfaces — Consumes:** Task 2's `observe(): AutoCloseable`. **Produces:** `TVCompanionApplication.light: LightController`, used by the Phase 2 gateway.

- [ ] **Step 1: Write the failing test**
```kotlin
@Test fun closingObserverDoesNotCloseControllerOrDropDone() {
    val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
    val screen = c.observe { }
    var r: LightResult? = null
    c.readThenExecute({ LightCommand.Power(false) }) { r = it }
    screen.close(); worker.complete()
    assertEquals(LightOutcome.CONFIRMED, r!!.outcome); assertEquals(0, lamp.closes); assertFalse(worker.stopped)
}
```
- [ ] **Step 2:** Run it. It should already PASS after Task 2. If it fails, fix `LightController`, not the test.
- [ ] **Step 3:** Make the app/screen changes above. `MainActivity.onStop`/`onDestroy` must no longer call `LightController.close()`. The first `showLight()` still calls `refresh()` as today.
- [ ] **Step 4:** `./gradlew test assembleDebug`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `Share one light controller between the TV screen and future callers`.

---

### Task 4: Ambilight public facade + `ConsentActivity`

**Files:**
- Create: `android/features/ambilight/src/main/java/id/poncol/tvcompanion/ambilight/Ambilight.kt`
- Create: `android/features/ambilight/src/main/java/id/poncol/tvcompanion/ambilight/ConsentActivity.kt` (the consent request + `onActivityResult` service start, moved from `AmbilightSettingsActivity.kt:197-243`)
- Modify: `android/features/ambilight/src/main/AndroidManifest.xml` (register `ConsentActivity`, `exported="false"`, `android:theme="@android:style/Theme.Translucent.NoTitleBar"`, `excludeFromRecents="true"`)
- Modify: `android/features/ambilight/src/main/java/id/poncol/tvcompanion/ambilight/AmbilightSettingsActivity.kt` (Start calls `Ambilight.requestStart`; remove its own `onActivityResult`/`pendingConsent`; disable the button while status is `NEEDS_APPROVAL` or `STARTING`. `observeStatus` stays as is.)
- Modify: `SessionController.kt` (add `internal val attached: Boolean get() = stopResources != null`)
- Create test: `android/features/ambilight/src/test/java/id/poncol/tvcompanion/ambilight/AmbilightFacadeTest.kt`

**Interfaces — Produces** (all functions main-thread only; the Phase 2 gateway posts to main):
```kotlin
enum class AmbilightStatus { OFF, NEEDS_APPROVAL, STARTING, RUNNING, STOPPING, NOT_SET_UP, ERROR }
enum class AmbilightStart { REQUESTED, BUSY, NOT_SET_UP, FAILED }
object Ambilight {
    fun status(context: Context): AmbilightStatus
    fun observe(context: Context, observer: (AmbilightStatus) -> Unit): AutoCloseable
    fun requestStart(context: Context): AmbilightStart   // begin() + launch ConsentActivity with FLAG_ACTIVITY_NEW_TASK and EXTRA_EPOCH
    fun stop()                                         // ambilightSession.stop()
    internal fun status(state: AmbilightState, configured: Boolean, attached: Boolean): AmbilightStatus
    internal fun startDecision(state: AmbilightState, configured: Boolean): AmbilightStart?  // null = may start
}
```
Mapping:
- `!configured` → `NOT_SET_UP`
- `STARTING && !attached` → `NEEDS_APPROVAL`
- `STARTING && attached` → `STARTING`
- `ACTIVE` → `RUNNING`
- `STOPPING` → `STOPPING`
- `ERROR` → `ERROR`
- `OFF` → `OFF`

`ConsentActivity` cancels the epoch with `Issue.CONSENT` if the activity is destroyed without a result, and always `finish()`es.

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun startDecisionRejectsUnconfiguredAndRunning() {
    assertEquals(AmbilightStart.NOT_SET_UP, Ambilight.startDecision(AmbilightState(), configured = false))
    assertEquals(AmbilightStart.BUSY, Ambilight.startDecision(AmbilightState(phase = Phase.ACTIVE), true))
    assertEquals(AmbilightStart.BUSY, Ambilight.startDecision(AmbilightState(phase = Phase.STARTING), true))
    assertNull(Ambilight.startDecision(AmbilightState(phase = Phase.ERROR, issue = Issue.CAPTURE), true))
}
@Test fun statusDistinguishesAwaitingApprovalFromStarting() {
    val s = AmbilightState(phase = Phase.STARTING)
    assertEquals(AmbilightStatus.NEEDS_APPROVAL, Ambilight.status(s, true, attached = false))
    assertEquals(AmbilightStatus.STARTING, Ambilight.status(s, true, attached = true))
    assertEquals(AmbilightStatus.RUNNING, Ambilight.status(s.copy(phase = Phase.ACTIVE), true, true))
    assertEquals(AmbilightStatus.NOT_SET_UP, Ambilight.status(AmbilightState(), false, false))
}
```
- [ ] **Step 2:** `./gradlew :features:ambilight:test --tests '*AmbilightFacadeTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement the facade, `ConsentActivity`, manifest entry and settings-screen switch-over.
- [ ] **Step 4:** `./gradlew :features:ambilight:test assembleDebug`. Expected: PASS (incl. existing `SessionControllerTest`), BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `Add a headless Ambilight facade and move consent into its own activity`.

---

### Task 5: Ambient shortcut + `ambientShowing` on the AIDL snapshot

**Files:**
- Modify: `android/core/domain/src/main/java/id/poncol/tvcompanion/domain/airplay/AirPlayReceiver.kt` (add `val ambientShowing: Boolean = false` as the last `AirPlaySnapshot` field)
- Modify: `android/ipc/airplay-contract/src/main/java/id/poncol/tvcompanion/airplay/ipc/AirPlayWire.kt:19-21` (flags range `0..255`, bit `128` → `ambientShowing`)
- Modify: `android/ipc/airplay-contract/src/main/aidl/id/poncol/tvcompanion/airplay/ipc/IAirPlayReceiver.aidl` (add `void setAmbientShowing(boolean showing);`)
- Modify: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/AirPlayService.kt` (hold `ambientShowing`, OR `128` into `sendState` flags, re-publish on change, reset to false when the binding activity unbinds)
- Create: `android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShortcut.kt`
- Modify: `AirPlayPlaybackActivity.kt` (read the request in `onCreate` and a new `onNewIntent`; apply it on the first state where `AmbientPolicy.canOpen`; save the look to its own `ambient` prefs before `openAmbient()`; call `setAmbientShowing(true/false)` in `openAmbient`/`closeAmbient`)
- Modify test: `android/ipc/airplay-contract/src/test/java/id/poncol/tvcompanion/airplay/ipc/AirPlayWireTest.kt`
- Create test: `android/integrations/airplay-runtime/src/test/java/id/poncol/tvcompanion/airplay/runtime/ambient/AmbientShortcutTest.kt`

**Interfaces — Produces:**
```kotlin
sealed class AmbientRequest { data class Open(val look: String) : AmbientRequest(); object Close : AmbientRequest() }
object AmbientShortcut {
    const val EXTRA_AMBIENT = "id.poncol.tvcompanion.ambient.REQUEST"   // "open" | "close"
    const val EXTRA_LOOK = "id.poncol.tvcompanion.ambient.LOOK"
    fun parse(action: String?, look: String?): AmbientRequest?          // null = ignore
    fun canOpen(snapshot: AirPlaySnapshot, look: String): Boolean      // AmbientPolicy.canOpen(snapshot) && look in AmbientShaders.LOOKS
    fun canClose(snapshot: AirPlaySnapshot): Boolean                   // snapshot.ambientShowing
    /** Returns false (nothing launched) when the snapshot doesn't allow it. Main process callers. */
    fun open(context: Context, snapshot: AirPlaySnapshot, look: String): Boolean   // requires AmbientPolicy.canOpen && look in AmbientShaders.LOOKS
    fun close(context: Context, snapshot: AirPlaySnapshot): Boolean               // requires snapshot.ambientShowing
}
```
Launch flags: `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP`, explicit component `AirPlayPlaybackActivity`.

- [ ] **Step 1: Write the failing tests**
```kotlin
// AmbientShortcutTest
@Test fun unknownLookIsRejected() {
    assertNull(AmbientShortcut.parse("open", "neon"))
    assertEquals(AmbientRequest.Open("dusk"), AmbientShortcut.parse("open", "dusk"))
    assertNull(AmbientShortcut.parse("explode", null))
}
@Test fun closeRequiresShowing() {
    assertEquals(AmbientRequest.Close, AmbientShortcut.parse("close", null))
    assertFalse(AmbientShortcut.canClose(AirPlaySnapshot(ambientShowing = false)))
    assertTrue(AmbientShortcut.canClose(AirPlaySnapshot(ambientShowing = true)))
}
// AirPlayWireTest: change the 0..127 loop to 0..255 and add
assertEquals(flags and 128 != 0, state.ambientShowing)
// and change the out-of-range assertion to flags = 256
```
- [ ] **Step 2:** `./gradlew :ipc:airplay-contract:test :integrations:airplay-runtime:test`. Expected: FAIL.
- [ ] **Step 3:** Implement the snapshot field, wire bit, AIDL method, service flag, shortcut and activity handling.
- [ ] **Step 4:** `./gradlew test assembleDebug`. Expected: PASS (incl. `AmbientPolicyTest`, `ReceiverSessionTest`), BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `Add an Ambient shortcut and report when Ambient is showing`.

---

### Task 6: AirPlay control reachable from `:app` without waking `:airplay`

**Files:**
- Modify: `android/features/airplay/src/main/java/id/poncol/tvcompanion/airplay/feature/AndroidAirPlayControl.kt` (`internal` → public; constructor `AndroidAirPlayControl(context: Context, autoCreate: Boolean = true)`; bind flags `if (autoCreate) BIND_AUTO_CREATE else 0`)

**Interfaces — Produces:** `AndroidAirPlayControl(context, autoCreate = false)`. Its snapshot stays `DISCONNECTED` while the service isn't running; Phase 2 reads that as `stopped`. `setKeepAvailable(true)` with `autoCreate = true` is the headless "on". Existing callers are unchanged (default `true`).

- [ ] **Step 1:** Make the change. Binding behaviour can't be unit-tested on the JVM; `AirPlayPresentationTest` must still pass.
- [ ] **Step 2:** `./gradlew :features:airplay:test assembleDebug`. Expected: PASS, BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `Expose AirPlay control with an option not to start the receiver`.

---

### Task 7: Phase 1 verification and hard stop

**Files:** Modify `docs/VERIFICATION.md` (append a "Quick Controls Phase 1" section with the device checks below, unchecked).

- [ ] **Step 1:** `./gradlew test assembleDebug` from clean (`./gradlew clean test assembleDebug`). Expected: all pass. Compare against the Task 0 baseline: no new failures.
- [ ] **Step 2:** `grep -rn "project(':features:" android/features android/integrations --include=build.gradle`. Expected: no output (no feature→feature dependency).
- [ ] **Step 3:** Write the device checks into `docs/VERIFICATION.md`:
  1. Light screen: refresh, on/off, presets, White, brightness all work as before. Leave and re-enter the screen, and the state is still there.
  2. Ambilight: Start from settings shows the prompt and runs; Stop works; the home card status is unchanged. Optional: `adb shell appops set id.poncol.tvcompanion PROJECT_MEDIA allow`, then Start shows no prompt.
  3. Record the overlay permission state: `adb shell appops get id.poncol.tvcompanion SYSTEM_ALERT_WINDOW` (Android lets apps holding it start activities from the background). A real background start of `ConsentActivity`/Ambient can only be tested once the Phase 2 gateway exists, so it is that plan's first device check.
  4. AirPlay music: the Ambient button still opens and closes. Back closes Ambient.
  5. Notifications and AirPlay screens unchanged; boot defaults unchanged.
- [ ] **Step 4:** Commit `Record Quick Controls Phase 1 device checks`.
- [ ] **Step 5: STOP.** Report to the owner: the tests run, their results, and the device check list. Do not start Phase 2 or write its plan until the owner confirms checks 1–5.
