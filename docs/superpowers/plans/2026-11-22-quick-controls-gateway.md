# Quick Controls — Phase 2 (Android control gateway) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The TV runs a small mutual-TLS listener on port 7380 that pairs the iPhone with a code shown on the TV and serves `contracts/controls-v1.md`. Every command calls the Phase 1 entry points, so the iOS Controls tab works end to end.

**Architecture:** All new code lives in `:app`, package `id.poncol.tvcompanion.app.control`. No new modules or processes.
- **Pure, JVM-tested pieces:**
  - `ControlWire`: frames, request parsing, responses, the PKCS#1 key form and the pairing proof.
  - `PairingWindow`: the code, its expiry and attempt counting.
  - `ControlDispatcher`: command → feature call → response, with no Android types.
  - `ControlServer`: the TLS socket loop, driven by an injected `SSLContext`.
- **Android glue:**
  - `ControlKeys`: an AndroidKeyStore RSA key behind TLS.
  - `AndroidControlFeatures`: implements the dispatcher's `ControlFeatures` with the Phase 1 entry points, hopping to the main thread with a timeout.
  - `ControlService`: a main-process `specialUse` foreground service.
  - `ControlPairingActivity`: the TV pairing screen, reached from a home button.
  - Boot/app-start wiring with its own `OnceGate`.

**Tech Stack:** Kotlin, Android SDK 26–34, `javax.net.ssl` (Conscrypt on device), `org.json` (Android; `org.json:json:20240303` for JVM tests, as `:features:notifications` already does), JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-22-quick-controls-design.md` (Phase 2). Wire contract `contracts/controls-v1.md`, which wins on names and values. The iOS client already ships against it (`ios/TVCompanionKit/Sources/TVCompanionKit/ControlWire.swift`).

## Global Constraints

- Branch `feature/quick-controls`. Install on the TV/iPhone only when the owner has said so for that test.
- Port **7380**; TLS 1.2+ with **mutual TLS**: the client certificate is required, and it is authorised only if `SHA-256(PKCS#1 client key)` is in the paired set (max 4).
- **Key form:** PKCS#1 `RSAPublicKey` DER = `SEQUENCE { INTEGER n, INTEGER e }`, the same bytes iOS gets from `SecKeyCopyExternalRepresentation`. The server key is RSA-2048, because iOS `TLSChannel` only pins RSA.
- **Framing:** a varint length prefix + UTF-8 JSON. Request ≤ **4096** bytes, response ≤ **16384**. One request per connection, **10 s** overall deadline, at most **2** concurrent connections (extras closed at once). Only site-local, link-local or loopback peers.
- **Pairing:** a 6-digit code (SecureRandom), valid **120 s**, **3** wrong proofs close the window. `proof = SHA-256(utf8(code) ‖ SHA-256(clientPKCS1) ‖ SHA-256(serverPKCS1))`, lowercase hex. Shared test vector: code `123456`, client key bytes `[1,2,3]`, server key bytes `[4,5,6]` → `bafb335964d3f985b7448791c0934cfdf7d7f481cdface0b7b5094214488cbf0`.
- **Outcomes** (wire strings): `ok pending busy unavailable unconfirmed needs_setup needs_approval invalid denied`. Status state strings come only from the contract table.
- **No logging** of codes, proofs, key hashes, addresses or request bodies.
- **The service runs only while a client is paired or a pairing window is open.** Boot start uses `controlsOnBoot`, default **true**.
- The dispatcher contains no feature logic beyond validation and the AirPlay confirm rule.
- Commands, from `android/`: `./gradlew :app:testDebugUnitTest` and `./gradlew test assembleDebug` (with `JAVA_HOME` = JDK 17).

## Review Focus

1. **A connection whose client certificate is not paired sends `status`.** Expect `{"outcome":"denied"}` with no status and no feature call (`unpairedClientIsDeniedWithoutFeatureCalls`).
2. **A third simultaneous connection, or one that never sends a full frame.** The third closes at once; the slow one is cut off at 10 s, and other connections keep working (`thirdConcurrentConnectionIsClosed`, `slowClientIsCutAtDeadline` with a shortened deadline).
3. **A feature throws or hangs while the status is being built.** Only that feature reports its failure state; the rest of the status is still returned (`oneFailingFeatureDoesNotBlankStatus`).
4. **A wrong proof three times, or a correct proof after expiry.** The window closes and nothing is paired (`pairingWindowExpiresAndLocksAfterThreeFailures`).
5. **AirPlay off while playing.** With `keepSession` off it returns `invalid`/`would_end_playback` unless `confirm:true`. With `keepSession` on it goes straight through (`airplayOffConfirmRule`).

---

### Task 1: `ControlWire` + `PairingWindow`

**Files:**
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/ControlWire.kt`
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/PairingWindow.kt`
- Modify: `android/app/build.gradle` (`testImplementation 'junit:junit:4.13.2'`, `testImplementation 'org.json:json:20240303'`)
- Create test: `android/app/src/test/java/id/poncol/tvcompanion/app/control/ControlWireTest.kt`

**Interfaces — Produces:**
```kotlin
object ControlWire {
    const val PORT = 7380; const val MAX_REQUEST = 4096; const val MAX_RESPONSE = 16_384
    fun readFrame(input: InputStream, max: Int = MAX_REQUEST): ByteArray   // throws IOException on EOF, a bad varint or oversize (before allocating)
    fun writeFrame(output: OutputStream, payload: ByteArray)
    fun pkcs1(key: java.security.interfaces.RSAPublicKey): ByteArray
    fun sha256(bytes: ByteArray): ByteArray
    fun proof(code: String, clientPkcs1: ByteArray, serverPkcs1: ByteArray): String   // lowercase hex
    fun parse(bytes: ByteArray): ControlCommand?    // null = malformed, unknown cmd, v != 1, or wrong field types
    fun response(outcome: String, status: JSONObject?, reason: String? = null): ByteArray
}
sealed class ControlCommand {
    object Status : ControlCommand(); data class Pair(val proof: String) : ControlCommand()
    data class Ambient(val open: Boolean, val look: String?) : ControlCommand(); data class Ambilight(val on: Boolean) : ControlCommand()
    data class Light(val on: Boolean) : ControlCommand(); data class LightPreset(val name: String) : ControlCommand()
    data class Notifications(val on: Boolean) : ControlCommand(); data class AirPlay(val available: Boolean, val confirm: Boolean) : ControlCommand()
}
class PairingWindow(private val now: () -> Long, random: java.security.SecureRandom = SecureRandom()) {
    val code: String                     // 6 ASCII digits, leading zeros kept
    val open: Boolean                    // false after 120 s, 3 failures, or a success
    fun attempt(proof: String, clientPkcs1: ByteArray, serverPkcs1: ByteArray): Boolean  // constant-time compare
}
```

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun proofMatchesSharedVector() = assertEquals("bafb335964d3f985b7448791c0934cfdf7d7f481cdface0b7b5094214488cbf0",
    ControlWire.proof("123456", byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))
@Test fun pkcs1IsSequenceOfModulusAndExponent() {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
    val der = ControlWire.pkcs1(key)
    assertEquals(0x30, der[0].toInt() and 0xff)
    // X.509 SubjectPublicKeyInfo ends with the PKCS#1 bytes inside its BIT STRING.
    assertArrayEquals(der, key.encoded.copyOfRange(key.encoded.size - der.size, key.encoded.size))
}
@Test fun framesRoundTripAndRejectOversizeBeforeReading() {
    val out = ByteArrayOutputStream(); ControlWire.writeFrame(out, ByteArray(300) { 7 })
    assertEquals(300, ControlWire.readFrame(ByteArrayInputStream(out.toByteArray())).size)
    val big = ByteArrayOutputStream(); ControlWire.writeFrame(big, ByteArray(5000))
    assertThrows(IOException::class.java) { ControlWire.readFrame(ByteArrayInputStream(big.toByteArray())) }
    assertThrows(IOException::class.java) { ControlWire.readFrame(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1, -1, -1))) }
}
@Test fun parsesContractRequestsAndRejectsBadOnes() {
    fun p(s: String) = ControlWire.parse(s.toByteArray())
    assertEquals(ControlCommand.Status, p("""{"v":1,"cmd":"status"}"""))
    assertEquals(ControlCommand.Ambient(true, "aurora"), p("""{"cmd":"ambient.set","look":"aurora","open":true,"v":1}"""))
    assertEquals(ControlCommand.Ambient(false, null), p("""{"cmd":"ambient.set","open":false,"v":1}"""))
    assertEquals(ControlCommand.AirPlay(false, true), p("""{"available":false,"cmd":"airplay.set","confirm":true,"v":1}"""))
    assertEquals(ControlCommand.AirPlay(true, false), p("""{"available":true,"cmd":"airplay.set","v":1}"""))
    assertEquals(ControlCommand.LightPreset("blue"), p("""{"cmd":"light.preset","name":"blue","v":1}"""))
    for (bad in listOf("""{"v":2,"cmd":"status"}""", """{"v":1,"cmd":"toggle"}""", """{"v":1,"cmd":"light.set","on":"yes"}""", "nope", """{"v":1,"cmd":"pair"}"""))
        assertNull(bad, p(bad))
}
@Test fun responseCarriesOutcomeReasonAndStatus() {
    val json = JSONObject(String(ControlWire.response("invalid", JSONObject().put("x", 1), "would_end_playback")))
    assertEquals(1, json.getInt("v")); assertEquals("invalid", json.getString("outcome"))
    assertEquals("would_end_playback", json.getString("reason")); assertEquals(1, json.getJSONObject("status").getInt("x"))
    assertFalse(JSONObject(String(ControlWire.response("denied", null))).has("status"))
}
@Test fun pairingWindowExpiresAndLocksAfterThreeFailures() {
    var t = 0L; val c = byteArrayOf(1); val s = byteArrayOf(2)
    val w = PairingWindow({ t }); assertTrue(w.code.matches(Regex("\\d{6}")))
    repeat(3) { assertFalse(w.attempt("0".repeat(64), c, s)) }
    assertFalse(w.open); assertFalse(w.attempt(ControlWire.proof(w.code, c, s), c, s))
    val late = PairingWindow({ t }); t = 120_001
    assertFalse(late.attempt(ControlWire.proof(late.code, c, s), c, s))
    t = 0; val ok = PairingWindow({ t })
    assertTrue(ok.attempt(ControlWire.proof(ok.code, c, s), c, s)); assertFalse(ok.open)
}
```
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest`. Expected: FAIL (unresolved `ControlWire`).
- [ ] **Step 3:** Implement both files and the test dependencies.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5:** Commit `Add the controls-v1 wire codec and pairing window`.

---

### Task 2: `ControlDispatcher` + `ControlFeatures`

**Files:**
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/ControlDispatcher.kt`
- Create test: `android/app/src/test/java/id/poncol/tvcompanion/app/control/ControlDispatcherTest.kt`

**Interfaces — Consumes:** Task 1 `ControlCommand`, `ControlWire.response`. **Produces:**
```kotlin
enum class Outcome(val wire: String) { OK("ok"), PENDING("pending"), BUSY("busy"), UNAVAILABLE("unavailable"), UNCONFIRMED("unconfirmed"), NEEDS_SETUP("needs_setup"), NEEDS_APPROVAL("needs_approval"), INVALID("invalid") }
data class AmbientStatus(val state: String, val look: String?, val looks: List<String>)
data class LightStatus(val state: String, val preset: String?, val presets: List<String>)
data class AirPlayStatus(val state: String, val available: Boolean, val connected: Boolean, val keepSession: Boolean)
/** Blocking calls made on a connection thread; implementations own main-thread hops and their own timeouts. */
interface ControlFeatures {
    fun ambient(): AmbientStatus; fun ambilight(): String; fun light(): LightStatus; fun notifications(): String; fun airplay(): AirPlayStatus
    fun setAmbient(open: Boolean, look: String?): Outcome; fun setAmbilight(on: Boolean): Outcome
    fun setLight(on: Boolean): Outcome; fun lightPreset(name: String): Outcome
    fun setNotifications(on: Boolean): Outcome; fun setAirPlay(available: Boolean): Outcome
}
class ControlDispatcher(private val features: ControlFeatures) {
    /** Paired clients only (the server handles pairing and denial). Returns response bytes. */
    fun handle(command: ControlCommand): ByteArray
    fun status(): JSONObject
}
```
Rules:
- **Failure states when a status getter throws:** ambient `unavailable`, ambilight `error`, light `unavailable`, notifications `failed`, airplay `failed`/`available:false`.
- **Validation:**
  - An `Ambient` open whose look is not in `ambient().looks` → `invalid`.
  - A `LightPreset` name not in `light().presets` → `invalid`.
  - A setter that throws → `unavailable`.
- **AirPlay confirm rule:** `AirPlay(false, false)` while `airplay().connected && !keepSession` → `invalid` with reason `would_end_playback`, and no call to `setAirPlay`.
- **Every response** carries `status()` built *after* the command. `Status` returns `ok`.

- [ ] **Step 1: Write the failing tests** (a `FakeFeatures` class in the test, with settable returns, a `calls` list and `throwOn` per method)
```kotlin
@Test fun statusSerialisesContractShape() { /* status() has ambient.state/look/looks, ambilight.state, light.state/preset/presets, notifications.state, airplay.state/available; no connected/keepSession keys */ }
@Test fun oneFailingFeatureDoesNotBlankStatus() { fake.throwOn += "light"; val s = dispatcher.status()
    assertEquals("unavailable", s.getJSONObject("light").getString("state")); assertEquals("listening", s.getJSONObject("notifications").getString("state")) }
@Test fun invalidLookAndPresetAreRejectedWithoutCalls() { assertOutcome("invalid", ControlCommand.Ambient(true, "neon")); assertOutcome("invalid", ControlCommand.LightPreset("teal")); assertTrue(fake.calls.none { it.startsWith("set") || it == "lightPreset" }) }
@Test fun airplayOffConfirmRule() {
    fake.airplay = AirPlayStatus("playing", true, connected = true, keepSession = false)
    val r = JSONObject(String(dispatcher.handle(ControlCommand.AirPlay(false, false))))
    assertEquals("invalid", r.getString("outcome")); assertEquals("would_end_playback", r.getString("reason")); assertFalse("setAirPlay" in fake.calls)
    assertOutcome("pending", ControlCommand.AirPlay(false, true))          // fake returns PENDING
    fake.airplay = AirPlayStatus("playing", true, connected = true, keepSession = true); fake.calls.clear()
    assertOutcome("pending", ControlCommand.AirPlay(false, false)); assertTrue("setAirPlay" in fake.calls)
}
@Test fun setterExceptionIsUnavailableAndOutcomesPassThrough() { fake.throwOn += "setLight"; assertOutcome("unavailable", ControlCommand.Light(true))
    fake.next = Outcome.BUSY; assertOutcome("busy", ControlCommand.Notifications(true)) }
```
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5:** Commit `Add the controls dispatcher`.

---

### Task 3: `ControlServer` + `ClientRegistry` (TLS loop, JVM-tested)

**Files:**
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/ControlServer.kt`
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/ClientRegistry.kt`
- Create test fixtures: `android/app/src/test/resources/control/server.p12`, `client.p12`, `stranger.p12`. These are test-only RSA-2048 identities made with `keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -storepass test`; document the command in the test's KDoc.
- Create test: `android/app/src/test/java/id/poncol/tvcompanion/app/control/ControlServerTest.kt`

**Interfaces — Consumes:** Tasks 1–2. **Produces:**
```kotlin
/** Paired client key hashes (hex of SHA-256(PKCS#1)), max 4, oldest dropped. Storage injected so the JVM test can use a map. */
class ClientRegistry(private val load: () -> Set<String>, private val save: (Set<String>) -> Unit) {
    fun isPaired(hash: String): Boolean; fun add(hash: String); fun clear(); val isEmpty: Boolean
}
class ControlServer(
    private val ssl: javax.net.ssl.SSLContext, private val serverPkcs1: ByteArray,
    private val registry: ClientRegistry, private val dispatcher: ControlDispatcher,
    private val pairing: () -> PairingWindow?, private val onPaired: () -> Unit,
    private val port: Int = ControlWire.PORT, private val deadlineMs: Int = 10_000,
) : AutoCloseable {
    val boundPort: Int
    fun start()            // binds; accept thread; at most 2 handler threads; extras closed at once
    override fun close()   // closes the socket and active connections
}
fun acceptAnyClientCertificate(): javax.net.ssl.X509TrustManager   // trust is decided by the registry after the handshake, never by CA chain
```
Handler:
- **Socket setup:** `needClientAuth = true` with the accept-any trust manager. Enabled protocols are restricted to TLSv1.2/TLSv1.3. The peer's address is checked before the handshake.
- **Identify the client:** after the handshake, `clientHash = hex(sha256(pkcs1(peer RSA key)))`.
- **Read and parse:** read one frame within the deadline, then `parse`. Malformed → `invalid`, no status.
- **`Pair`:** check `pairing()?.attempt(...)`. Success → `registry.add`, `onPaired()`, reply `ok`. Otherwise reply `denied`.
- **Other commands:** not paired → `denied`; paired → `dispatcher.handle`.
- **Errors:** close quietly. Log nothing.

- [ ] **Step 1: Write the failing tests**, using a real `SSLContext` from the fixtures, a client `SSLSocket` with `client.p12` and a trust-all client trust manager, and a `FakeFeatures` from Task 2 (move it to a shared test file):
```kotlin
@Test fun pairedClientGetsStatus()                       // registry pre-seeded with client hash → outcome ok + status present
@Test fun unpairedClientIsDeniedWithoutFeatureCalls()    // stranger.p12 → {"outcome":"denied"}, fake.calls empty
@Test fun pairingWithCorrectProofRegistersClient()        // window from test; proof from client/server PKCS#1 → ok; then status → ok
@Test fun wrongProofIsDeniedAndNotRegistered()
@Test fun thirdConcurrentConnectionIsClosed()             // two connections hold (no request sent); third read() returns -1 / SSLException promptly
@Test fun slowClientIsCutAtDeadline()                     // deadlineMs = 300; connect, send nothing; socket closed within ~1 s; a following client still served
```
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3:** Implement; generate the fixtures.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5:** Commit `Add the mutual-TLS control server`.

---

### Task 4: Android wiring — keys, features, service, pairing screen, startup

**Files:**
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/ControlKeys.kt`
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/AndroidControlFeatures.kt`
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/ControlService.kt`
- Create: `android/app/src/main/java/id/poncol/tvcompanion/app/control/ControlPairingActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`:
  - the service: `exported="false"`, `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` "User-paired local network remote control for TVCompanion features";
  - the activity: `exported="false"`;
  - permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.
- Modify: `android/core/domain/src/main/java/id/poncol/tvcompanion/domain/AutoStartSettings.kt` (`var controlsOnBoot: Boolean`) and `TVCompanionApplication.kt` (pref `controls`, default `true`).
- Modify: `BootReceiver.kt`. A `controlsDefault = OnceGate()` starts `ControlService` on boot (if `controlsOnBoot`) and in `startDefaults`, only when `ControlService.hasPairedClients(context)`.
- Modify: `MainActivity.kt`. A "Pair iPhone for Controls" button under the home hint opens `ControlPairingActivity`.
- Modify: `android/app/src/main/res/values/strings.xml` (new copy).

**Behaviour:**
- **`ControlKeys.context(): Pair<SSLContext, ByteArray>`**
  - Alias `tvcompanion_controls_v1` in AndroidKeyStore, generated once.
  - Key spec: RSA 2048, `PURPOSE_SIGN`, digests NONE/SHA-256/SHA-384/SHA-512, signature paddings PKCS1 + PSS, and a self-signed cert subject `CN=TVCompanion Controls`.
  - TLS setup: `KeyManagerFactory.getDefaultAlgorithm()` initialised with that KeyStore, plus `acceptAnyClientCertificate()`.
  - Returns the server PKCS#1 bytes.
  - If this fails, the service stops and the pairing screen shows "Controls can't start on this TV".
- **`AndroidControlFeatures(context, light: LightController)`**, using `onMain(timeoutMs) { resume -> … }` (Handler + CountDownLatch):
  - **Ambient:**
    - Holds `AndroidAirPlayControl(context, autoCreate = false)`, created and closed by the service on the main thread.
    - `ambient()`: `showing` if `snapshot.ambientShowing`; else `available` if `AmbientShortcut.canOpen(snapshot, AmbientShaders.DEFAULT_LOOK)`; else `unavailable`.
    - The look comes from `getSharedPreferences("ambient", MODE_MULTI_PROCESS)` (written by `:airplay`). `looks = AmbientShaders.LOOKS`.
    - `setAmbient` → `AmbientShortcut.open/close`: true → `PENDING`, false → `INVALID`.
  - **Ambilight:**
    - `ambilight()` maps `Ambilight.status` to the contract strings.
    - `setAmbilight(true)` maps `requestStart`: REQUESTED → `PENDING`, BUSY → `BUSY`, NOT_SET_UP → `NEEDS_SETUP`, FAILED → `UNAVAILABLE`.
    - `false` → `Ambilight.stop()` → `PENDING`.
  - **Light:**
    - `light()` → `light.refresh(done)` (wait ≤ 6 s).
      - `CONFIRMED` → `on`/`off`. The preset is `white` in white mode, or the `LightPresets.HUES` name whose hue matches at saturation 1000 in colour mode.
      - `BUSY` → `busy` with the last state's preset.
      - Otherwise `unavailable`.
    - `setLight` / `lightPreset` → `readThenExecute`: CONFIRMED → `OK`, BUSY → `BUSY`, UNSUPPORTED → `INVALID`, UNCONFIRMED → `UNCONFIRMED`, otherwise `UNAVAILABLE`.
  - **Notifications:**
    - `notifications()` maps `AndroidNotificationRuntime(context, ReceiverSettings(context)).snapshot.phase`: LISTENING → `listening`, STARTING → `starting`, FAILED → `failed`, otherwise `paused`.
    - `set(true)` → `UNAVAILABLE` if `!ReceiverService.canStart(context)`, else `start()` → `PENDING`.
    - `false` → `pause()` → `PENDING`.
  - **AirPlay:**
    - `airplay()` from the snapshot: DISCONNECTED/STOPPED → `stopped`; READY+PLAYING → `playing`; READY → `ready`; STARTING/STOPPING → `starting`; FAILED → `failed`.
    - Also from the snapshot: `available = keepAvailable`, `connected = connection != NONE`, and `keepSession`.
    - `setAirPlay(true)`: if bound → `setKeepAvailable(true)` (accepted → `PENDING`). Otherwise `startForegroundService(ACTION_APP_START + EXTRA_FOREGROUND_START)` → `PENDING`.
    - `false` → `setKeepAvailable(false)` → `PENDING`, or `OK` when not running.
- **`ControlService`:**
  - Foreground notification "iPhone controls ready".
  - Builds keys, `ClientRegistry` (prefs `controls`, key `clients`), dispatcher and server.
  - `ACTION_PAIR` opens a `PairingWindow` published through a process-local `ControlPairing` object; the activity observes it.
  - `ACTION_FORGET` clears the registry and stops.
  - It stops itself when the registry is empty and no pairing window is open (window expiry posts a check).
  - `hasPairedClients(context)` reads the prefs.
- **`ControlPairingActivity`:** `TvUi` page.
  - "Pair iPhone" shows the code large, a 2-minute countdown and the instruction "On your iPhone, open TVCompanion → Controls and enter this code".
  - On success it shows "Paired".
  - "Forget paired iPhones" is shown when the registry is not empty.
  - It starts the service with `ACTION_PAIR` on open.

- [ ] **Step 1:** Implement all of the above.
- [ ] **Step 2:** `./gradlew test assembleDebug`. Expected: all tests pass, BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `Run the control gateway on the TV with a pairing screen`.

---

### Task 5: Device verification (owner-authorised installs only)

**Files:** Modify `docs/VERIFICATION.md` ("Quick Controls end to end").

- [ ] **Step 1:** Install the APK (`adb install -r`), then open Pair iPhone on the TV via adb and screenshot the code.
- [ ] **Step 2:** The owner enters the code on the iPhone Controls tab. Expected: Paired on both sides, and the iPhone shows the status of all five features.
- [ ] **Step 3:** Exercise each control from the iPhone and confirm on the TV via screenshots and `dumpsys`:
  - Notifications off → TV paused → on.
  - Light on/off and a preset, then restore the original state.
  - Ambilight on (prompt or no prompt, and a background launch) → running → off.
  - AirPlay background off/on.
  - Ambient open/close while music plays (needs the owner's music).
- [ ] **Step 4:** Notifications off from iOS, then on again from iOS. The control link survives.
- [ ] **Step 5:** Record the results, commit and push.
