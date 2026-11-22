# Quick Controls — design

Date: 2026-11-22 · Branch: `feature/quick-controls` (from `origin/main` b8982f3) · Status: approved and implemented (Phases 1–3 device-checked 2026-11-22)

## Intent

From the iPhone app, switch existing TV features on and off without walking to the TV and opening their screens. The iOS app gains a **Controls** tab; the TV gains one small, secure control listener. The listener must stay separate from every feature, so that switching one feature off (notably the notification receiver) never takes remote control down with it.

## Goals

- Five controls, each showing the state the TV actually reports:

| Feature | Controls | Status shown |
|---|---|---|
| Ambient | Play Ambient (with look) / Close | unavailable (no AirPlay music) · available · showing |
| Ambilight | Start / Stop | off · starting · running · needs approval on TV · not set up · error |
| Bardi light | On · Off · colour presets (Red, Orange, Yellow, Green, Blue, Purple) · White | on/off, current preset, unavailable, unconfirmed |
| Notifications | Receiver on/off | listening · paused · failed |
| AirPlay | Available in background on/off | stopped · ready · playing |

- Run each operation without opening its Android screen.
- Pair the iPhone once, using a code shown on the TV. Unpaired clients get nothing.

## Non-goals

- Sleep or timers for Bardi (dropped by owner). Brightness slider (easy to add later).
- An auto-open Ambient setting. Ambient is a shortcut only; AirPlay behaviour is unchanged.
- Remote strip setup, Ambilight calibration and brightness, notification PIN/duration/sources, AirPlay receiver name.
- New Gradle modules, new processes, background polling, a generic device/automation registry.

## Anti-goals (failure even if it works)

- Notifications off ⇒ remote control off.
- iOS shows a state the TV did not confirm.
- A toggle-style command that can flip the wrong way after an uncertain write.
- The notification PIN, or any plaintext channel, authorises physical control.
- Changing an AirPlay preference silently ends current playback.
- The control layer gets into the TV's code and slows or breaks existing screens.

## Constraints found in the audit

- Gradle modules already have no feature→feature dependencies; only `:app` composes them. Isolation work is about headless entry points, not module boundaries.
- `LightController` is created and closed by `MainActivity` for each visit to the screen. The colour presets are hard-coded in `LightScreen`. A second, independent controller would race the UI on the lamp.
- Ambilight state (`ambilightSession`) is in-process and `internal`. Starting needs MediaProjection consent, launched from `AmbilightSettingsActivity`.
- Ambient is a button inside `AirPlayPlaybackActivity` (`:airplay` process), allowed only by `AmbientPolicy.canOpen`. The look is saved in `:airplay`'s `ambient` prefs.
- AirPlay already has a headless AIDL control (`AndroidAirPlayControl`, `internal` to `:features:airplay`).
- Notifications already has `AndroidNotificationRuntime.start()/pause()` + `ReceiverStatusStore.snapshot`.
- The only LAN listener today is the notification receiver's plaintext `LocalServer`.
- iOS already has mutual-TLS plumbing: `TLSChannel` (pinned RSA server key, client identity), `RemoteIdentityStore`, `CredentialStore`, and `FrameDecoder` (varint length-prefixed frames, 64 KB cap).
- The Bardi lamp is currently unlinked (local key lost). Light control can only be tested with fakes until it is re-provisioned.
- Owner rule: never install to the TV unless asked. Build and unit test locally; device checks are listed for the owner.

## Approaches

1. **Separate always-on control listener in the main process** *(owner picked)*. A dedicated foreground service owns the TLS listener and dispatches to each feature's headless entry point. Costs one more foreground service, but any feature can be controlled independently, including turning notifications back on.
2. Host control inside the notification receiver. No new service, but switching notifications off locks out remote control. That breaks the main goal.
3. Relay through the Google TV remote channel. It can't carry custom commands. Rejected.

## Design

### Phase 0 — baseline

Run the existing Android `./gradlew test` and the iOS Kit/Audio package tests on this branch before any change, and record the results.

### Phase 1 — headless entry points (no network yet)

The smallest refactor that lets non-UI code drive each feature. The screens move onto the same entry points, so there is one code path per operation.

1. **Light: one shared owner per process.** Each remote light command reads then writes inside one worker job, so presets never build on hours-old state. Every path calls `done` exactly once: busy, no state yet, exception, closed.
   - `TVCompanionApplication` lazily owns a single `LightController(TuyaLightGateway)`. `LightController` gains multi-observer `observe(): AutoCloseable` and a per-call completion: `execute(command, done: (Outcome) -> Unit)`, `refresh(done)`.
   - Busy calls are rejected immediately with `BUSY`, never queued.
   - `MainActivity` observes and un-observes instead of closing the controller. The controller and gateway close only when the process dies. `TuyaLightGateway` opens a socket for each operation, so idle cost is zero; check this during implementation.
   - Move the preset list (name→hue) and the White command out of `LightScreen` into `core:domain` (`LightPresets`), so the UI and the remote share it.
2. **Ambilight: public facade** in `:features:ambilight`, `object Ambilight { fun state(): AmbilightStatus; fun observe(cb): AutoCloseable; fun stop(context); fun requestStart(context) }`.
   - `requestStart` launches a new transparent `ConsentActivity`. It holds the consent + `ACTION_START` logic moved out of `AmbilightSettingsActivity`, and the settings screen uses it too.
   - "Not set up" comes from `AmbilightPreferences` (no controller address).
   - If `appops PROJECT_MEDIA allow` is granted, Android returns consent without a dialog. Otherwise the TV shows the prompt and the status reads "needs approval on TV".
3. **Ambient: intent shortcut.** `AirPlayPlaybackActivity` accepts `EXTRA_AMBIENT = open|close` and `EXTRA_LOOK`. It saves the look in its own process (no cross-process prefs writes) and opens or closes only if `AmbientPolicy.canOpen`.
   - Showing/not showing is reported back through one new bit in the existing AIDL snapshot `flags` (`ambientShowing`). Add `ambientShowing` to `AirPlaySnapshot`.
   - The playback activity tells its in-process service when Ambient opens and closes.
4. **AirPlay:** make `AndroidAirPlayControl` reachable from `:app` (public, or a public factory), with an option to bind *without* `BIND_AUTO_CREATE`. `status` must not start `:airplay`: if the service isn't running, report `stopped`. Only `airplay.set {available:true}` auto-creates, and it uses the same path as `ACTION_APP_START`. `setKeepAvailable(true)` already starts the receiver with its default name.
5. **Notifications:** no change. Reuse `AndroidNotificationRuntime` and the existing `STATE_CHANGED` broadcast.

**Phase 1 checkpoint:** all existing unit tests pass. Add new tests for shared-light serialization (UI + second caller → `BUSY`, completion delivered once, observer close). Debug APK builds. **Hard stop:** the builder hands over and waits until the owner has run device checks 1 and 3. Phase 2 starts only after the owner confirms.

### Phase 2 — control gateway (Android, `:app`, package `app.control`)

- **`ControlService`:** main-process foreground service (`specialUse`, like `ReceiverService`).
  - Started from `BootReceiver`/`startDefaults` with its own `OnceGate`. Runs only while at least one iPhone is paired or a pairing window is open; otherwise it stops itself.
  - New `AutoStartSettings.controlsOnBoot`, default on.
- **Transport:** TLS 1.2+ over TCP, fixed port **7380** *(builder's call)*.
  - Server key: RSA-2048 in AndroidKeyStore with its auto-generated self-signed cert. RSA because iOS `TLSChannel` already pins RSA keys.
  - Mutual TLS: the client cert is required and checked against the paired client key pins after the handshake.
  - Frames: the existing varint length prefix with UTF-8 JSON, ≤ 4 KB per request.
  - One request and one response per connection. 10 s total deadline per connection; at most 2 concurrent connections, extras refused.
- **Pairing:**
  - The TV owner opens "Pair iPhone" on a small new TV screen, reached from a home card or row. The TV shows a 6-digit code for 2 minutes.
  - iOS connects without a pin (pairing only, as `TLSChannel` already allows) and sends `proof = SHA-256(code ‖ clientKeyHash ‖ serverKeyHash)`. Binding the key hashes stops a captured proof being replayed to a different server. It does **not** stop an active attacker on the LAN during the 2-minute window: a 6-digit code can be brute-forced offline from the proof. That attacker is out of scope, the same model as Google TV's own pairing. Upgrade if wanted: both screens show 4 digits of `H(clientKeyHash‖serverKeyHash)` and the owner confirms on the TV.
  - On success the TV stores the client key hash (max 4 clients) and iOS stores the server key pin in Keychain for that TV profile.
  - 3 wrong proofs close the window. The same screen has "Forget paired iPhones".
- **Replay/idempotency:** mutual TLS gives freshness per connection. Every command sets an explicit state (`on: true/false`, `preset: "blue"`), so repeating one is harmless. There's no request-ID cache and no expiry field. Revise `contracts/client-commands-draft.md` to this and promote it to `contracts/controls-v1.md`.
- **Commands (v1):**
  - `status` returns all five features.
  - `ambient.set {open, look?}`, `ambilight.set {on}`, `light.set {on}`, `light.preset {name}` (incl. `white`), `notifications.set {on}`, `airplay.set {available}`.
  - The status payload includes the Ambient look list (`AmbientShaders` names).
- **Outcomes:** each command returns the feature's state after the operation, plus one of `ok · pending · busy · unavailable · unconfirmed · needs_setup · needs_approval · invalid · denied`.
  - `pending` is for start-type operations that confirm later (Ambilight, AirPlay); iOS re-reads `status` once after ~2 s and on refresh, without polling.
- **Dispatcher:** a thin `when` over the command. Each branch calls one Phase 1 entry point under its own timeout (light 6 s, others 5 s). A thrown or timed-out branch returns `unavailable`/`unconfirmed` for that feature only. The dispatcher contains no feature logic.
- **AirPlay safety:** `setKeepAvailable(false)` already keeps a connected session alive when `keepSession` is on (the default): `heldInBackground = keepAvailable || (keepSession && connected)`. So only when a session is connected **and** `keepSession` is off does `airplay.set {available:false}` return `invalid` with reason `would_end_playback`, unless the request carries `confirm: true`. iOS then asks the user.
- **Logging:** no keys, codes, client hashes or addresses in logs.

**Phase 2 checkpoint:** JVM unit tests cover:
- the codec: oversize, bad JSON, unknown command;
- the pairing proof: wrong code, wrong key hash, throttle;
- the dispatcher, with fake features for busy, unavailable, timeout, and one feature throwing while the others still answer;
- the AirPlay confirm rule.

The real TLS listener is exercised once with a local JVM test where feasible. AndroidKeyStore-backed TLS is a device check.

### Phase 3 — iOS Controls tab

- `TVCompanionKit/ControlWire.swift`: Codable request/response types and the pairing proof, with tests in `CompanionTests`.
- `Infrastructure/ControlClient.swift`: reuses `TLSChannel`, `RemoteIdentityStore` (same client identity) and `CredentialStore` (per-profile server pin). It does one request per call.
- `Features/Controls/ControlsView.swift` + `ControlsViewModel.swift`, added as a third tab, "Controls" (`switch.2`). Remote and Send message stay unchanged.
  - Five grouped `List` sections in the existing tint.
  - Pairing sheet when unpaired.
  - Refresh on tab appear, on pull-to-refresh, and after each command response. Suspended with the other models on background.
  - Each control shows a spinner while waiting and displays only the state the TV returns. Unreachable TV → one banner and disabled controls.
  - Ambient look picker as a menu.
  - When the TV answers `would_end_playback`, iOS asks "This stops the current playback".

**Phase 3 checkpoint:** iOS Kit tests pass; the app builds for the simulator. The UI is checked against a fake client in previews.

## Owner device checks (not run by the builder)

1. Existing screens unchanged after Phase 1 (Light, Ambilight start/stop, AirPlay, Notifications).
2. `adb shell appops set id.poncol.tvcompanion PROJECT_MEDIA allow`, then remote Ambilight Start shows no prompt. Without it, the prompt appears and iOS shows "needs approval".
3. Background activity launch works for `ConsentActivity` and Ambient while the app is not on screen. This relies on the overlay permission notifications already hold.
4. AndroidKeyStore key serves TLS; pairing, then status, works from the iPhone; "Forget" locks the iPhone out.
5. Notifications off from iOS, then back on from iOS. The control link survives.
6. Bardi on/off/preset after the lamp is re-provisioned; UI and iPhone at the same time → one `busy`.
7. AirPlay off while playing (default settings) → playback continues and the receiver stops after the session ends. With "keep session" off → iOS confirmation shown first.

## Builder's calls (owner may override)

- Port 7380; RSA-2048 server key; one request per connection; 10 s connection deadline; max 2 connections, max 4 paired iPhones.
- `ControlService` runs only while something is paired. Boot start defaults on.
- Pairing lives on a small new TV screen, not inside an existing feature screen.
- No request-ID/expiry replay cache; relies on mutual TLS plus explicit-state commands.
- Pairing threat model: an active LAN attacker during the pairing window is out of scope (no fingerprint confirmation).

## Left to the builder

Exact JSON field names, the TV pairing screen layout (match `TvUi`), string copy, the iOS section layout within the existing style, and the AIDL flag bit position.

## Main risks

- AndroidKeyStore keys behind `SSLServerSocket` (KeyManagerFactory) must work on the Skyworth's Android version. Spike this first in Phase 2. Fallback: an RSA key in an app-private PKCS12 encrypted with a Keystore AES key.
- Background activity starts (Ambilight consent, Ambient) depend on device policy. On failure the status reports `needs_approval`/`unavailable`, never a fake success.
