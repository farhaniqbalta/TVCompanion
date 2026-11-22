# Verification — 2026-10-28

## Phase 1 installed and user-verified: 0.3.0-phase1-preview

Branch: `review/phase-1-architecture`. The user approved implementation after assessment. The refactor is committed in focused product-role, Tuya and notification steps; see [implementation changelog](phase-1/IMPLEMENTATION_CHANGELOG.md).

Executed locally after the relevant implementation steps:

```sh
cd android
./gradlew --offline --no-daemon :app:assembleDebug :core:domain:test :integrations:tuya-lan:testDebugUnitTest :features:notifications:testDebugUnitTest :app:lintDebug
```

- Assembly succeeded for version code 4 / `0.3.0-phase1-preview`.
- **59 tests passed, zero failures/errors.** Existing 33 cases remain, with four absolute-deadline/fragment tests, seven gateway orchestration/cancellation tests and fifteen receiver-session lifecycle tests added. The existing controller-close test now also verifies gateway ownership and idempotent cleanup.
- Android app/library lint: **zero findings**. Existing compiler/SDK tooling notices remain distinct from lint findings.
- Rechecked pure Kotlin/JDK domain imports, unchanged module dependency direction, source XML/doc links, whitespace and the absence of new always-on workers, scheduled polling, command endpoints, Binder/AIDL/native media code or workflow changes.
- Notification tests include a real ephemeral loopback listener with incomplete input and port release on session stop. All lamp tests use synthetic sessions/input; no real lamp command or private configuration is accessed.
- New command and external receiver contracts are documents only. They are not live APIs and do not provide AirPlay feasibility evidence.

**Device installation smoke check — 2026-10-28:** at the user’s request, updated the existing TV application to version code 4 / `0.3.0-phase1-preview` with `adb install -r`. Android confirmed installation and package metadata confirmed the version. The existing encrypted lamp setup was present before and after; the one-time plaintext import was absent. Native authenticated lamp status was confirmed on screen, reporting the light off. The notification receiver was active and its settings UI showed the Pause action. The dashboard was verified and left open. Verification used read-only UI dumps and explicit app entry intents; no power/brightness input, overlay-permission change or benchmark was performed. Temporary device UI dumps were removed. No private endpoint, PIN or device identifier is recorded here.

**User acceptance — 2026-10-28:** after installation, the user confirmed that the lamp and notifications work and that all is good. This accepts the Phase 1 baseline for the tested everyday functionality.

**Still pending:** dedicated queue/dedup/expiry fault checks on the TV, offline/standby recovery and broader WindowManager/player coexistence. User confirmation of working features is not evidence of those separate failure scenarios. ADB closure remains pending after the user finishes testing. No Phase 2 work has begun; GitHub Actions remains postponed.

## Previously installed build: 0.2.1-brand-preview

- Gradle debug APK assembly succeeded with JDK 17 and Android SDK 34.
- All 33 functional/regression tests passed: six light-controller tests, five notification-controller tests, two queue tests, nine event-validation tests, three HTTP transport/lifecycle tests, and eight Tuya protocol tests.
- Android app lint, including Android feature/library dependencies, reports zero errors and zero warnings. The custom views are deliberately constructed with state/controller arguments, so XML-inflation constructor checks are narrowly annotated. The pure Kotlin module is compiled and unit-tested separately; Android lint does not analyze it.
- Branding assets are generated from original geometry with the Python standard library: SVG masters, adaptive color/monochrome icons, a 320×180 TV banner, shared headers and a monochrome service icon.
- New direct style/duration selection is validated before preferences change. The UI uses resource strings and plurals.
- Git diff whitespace checks passed. Device captures remain in ignored `artifacts/ui/`, outside tracked source. No credentials or private addresses are included in the research or design notes.

## TV validation

The original foundation was installed and provisioned successfully. Encrypted lamp storage was present and its one-time plaintext import removed. Native lamp status and the dashboard were verified. The user subsequently confirmed that the functionality works.

The UI preview was installed as an update, preserving the existing app identity, signing configuration and private data. Screenshots were inspected for Home, light controls, notification Appearance and Sources. The light layout fits on the current TV without scrolling. The notification overlay renderer/dimensions remain unchanged.

An initial injected-key review assumed the starting page was Home. Captures showed an active light screen, so a center/OK event may have activated its power action. This is not recorded as a deliberate hardware-control test. That sequence was stopped and replaced with checks that inspect the UI and allow only identified navigation labels. The later review activated navigation controls only. Physical remote confirmation of the refreshed focus behavior still belongs to the user; injected events are not a substitute.

The final 0.2.1 branding preview was also installed as an update, and the branded dashboard was captured and visually inspected. The app is left on the new dashboard. App updates may stop a running receiver; open Receiver and start it if needed. No overlay permissions, pairing PINs, source filters, or lamp configuration were deliberately changed by the UI review. ADB closure remains a user-completed step after testing; it has not been verified as closed.

## Resource behavior

No lamp polling, background dashboard animation, wake lock, boot receiver, automatic write retry or recurring UI timer is introduced. The single light worker is closed with the screen, and backgrounding releases screen view trees. Only the explicitly started notification receiver stays active; its listener blocks awaiting work and closes its current connection on pause. HTTP inputs, event acceptance, remembered IDs and event queues remain bounded.

No CPU/RAM benchmark was run, as requested. These are code properties, not measured performance guarantees.

## Remaining work

Narrow/large-text fallbacks are implemented but not hardware-tested across configurations. Fully offline behavior, standby/reboot recovery and protected-player coexistence still require dedicated validation. Automatic iPhone notification capture is not established. Apple remote implementation, Mac support, release onboarding, encrypted notification transport, stronger pairing, additional translations and license selection remain open work.

The Android CI workflow is configured; this record describes local checks rather than a new GitHub Actions run.


## Phase 1 assessment baseline — 2026-10-28

Review branch: `review/phase-1-architecture`. Application source baseline: `555d4be`. The handoff was read and source-audited before any proposed refactor. This assessment adds documents only; no implementation or TV changes occurred.

The README's assembly/domain/unit/lint tasks were independently re-executed locally with `--offline --no-daemon --rerun-tasks`, using JDK 17 and Android SDK 34. Gradle reported `BUILD SUCCESSFUL` and 221 tasks executed. Results: 33 tests, zero failures/errors; app lint including Android libraries: zero findings. The existing compiler deprecation notice for legacy Back handling, SDK tooling XML-version notice, and JVM-domain exclusion notice remain build-tool/compiler notices, not lint findings.

The inspected Gradle dependency graph is acyclic. Domain/application source imports contain no Android, AndroidX, JSON or integration-adapter dependencies. No AIDL/native media, boot receiver, wake lock or scheduled polling was found in the application sources.

Current tests cover six light-controller cases, five notification-controller cases, two queue cases, nine admission cases, three local HTTP transport cases and eight Tuya frame/crypto/address cases. They do not independently exercise real gateway write/readback sequencing, active Tuya socket cancellation, Android foreground service failures, overlay failures or AirPlay.

No deployment, ADB session, real-lamp command, credential read, device trace or CPU/RAM benchmark was performed. Previously recorded hardware/user confirmation is historical evidence rather than a new assessment result. The GitHub Actions issue remains explicitly postponed and the workflow is unchanged.

See [current architecture](phase-1/CURRENT_ARCHITECTURE.md), [target proposal](phase-1/TARGET_ARCHITECTURE.md), and [migration plan](phase-1/MIGRATION_PLAN.md). Application implementation awaits approval of the proposed changes, as requested by the handoff.

## Phase 2 integrated video preview — 2026-10-29

See the [single-APK integration record](phase-2/SINGLE_APK_INTEGRATION.md): 73 passing tests, successful main/lab builds, main lint with zero errors and one target-ABI warning, update with existing preferences preserved byte-for-byte, and user acceptance of the integrated preview. No further testing was performed after the user requested stopping checks. Audio, idle background availability, broader device compatibility and distribution licensing remain open.

## Audio and background candidate — 2026-10-29

The [0.5 candidate](phase-2/AUDIO_AND_BACKGROUND.md) builds with 81 passing JVM tests and passing native clock/H.264/ALAC round-trip checks. Lint: zero errors, one known ChromeOS/ABI warning. No automated TV session, audio listening, background-mode test or benchmark was run; the user requested installation and will provide device review.

The audio/background candidate was subsequently installed at the user’s explicit request. Existing preference files were preserved byte-for-byte; no notification receiver was running before the update. The dashboard was opened without starting AirPlay. Audible output, lip-sync and idle/background behavior remain pending user review.

## 0.5.2 local lifecycle/video correction

Built versionCode 8 with the four separate background/presentation controls, protocol v3 and bounded startup-video recovery. All 89 JVM tests pass, including the Phase 1 suites; native helper checks pass; lint has zero errors and the known ChromeOS ABI warning. Spotify AirPlay source/implementation review and deferred manual checks are recorded in [AUDIO_AND_BACKGROUND.md](phase-2/AUDIO_AND_BACKGROUND.md). The user has no TV access: this candidate was not installed and neither the video fix nor Spotify playback is hardware-verified. No commit/push is included.

## 0.5.3 AirPlay UI/UX candidate

VersionCode 9 adds grouped responsive settings, connection instructions, independent status facts, background-audio dependency feedback, explicit leave/stop behavior, focused retry paths and an on-demand video toolbar. Build and 95 JVM tests pass; lint has zero errors and the known ChromeOS ABI warning. New tests distinguish audio from video and retained sessions from discovery, verify stale display issues clear on reattachment, and round-trip the v4 status flags. Phase 1 suites passed during this change. No TV installation/connection or visual acceptance testing was performed because the user is away from the TV. Home testing remains pending.

## 0.6.0 Bardi color-control preview

Branch `feature/bardi-color-control` starts from pushed AirPlay UI commit `1f60f7d`. VersionCode 10 builds, all 105 JVM tests pass, and lint reports zero errors plus the existing ChromeOS ABI warning. New coverage checks bounded HSV16 parsing, atomic mode/color group validation, separate mode-and-color readback, unsupported/off states, white-mode restoration and pure/controller adjustment behavior. No TV connection, lamp write, installation or benchmark was performed. Physical color and remote navigation remain pending; see [BARDI_COLOR_CONTROL.md](BARDI_COLOR_CONTROL.md).

The user subsequently authorized installation after returning home. The 0.6.0 APK update succeeded; all pre-existing preference files were preserved byte-for-byte and the dashboard opened. The notification receiver was not previously running. No lamp write or AirPlay session was initiated; user hardware review and eventual ADB closure remain pending.

The user subsequently reported “all works” after the requested Bardi color-control test sequence. Presets, HSV adjustments and returning to white mode are accepted by user report; exhaustive edge cases and fully offline operation are not claimed. This acceptance does not resolve pending AirPlay video/Spotify checks or verify ADB closure.

## AirPlay home acceptance and ALAC correction

The AirPlay home-test update (same 0.5.3 candidate, versionCode 11) subsequently passed a fresh build, 95 JVM tests, native checks and lint, then installed successfully over Bardi build 10 with all existing preferences preserved. Only the dashboard was opened; user AirPlay playback/UX feedback and ADB closure remain pending. See [installation record](phase-2/AUDIO_AND_BACKGROUND.md).

Home feedback confirms iPad video/pictures, iPad screen mirroring and Mac extended desktop; Spotify audio-only remains silent despite confirmed background settings. Diagnostic build 12 installed with saved preferences preserved after passing build/focused tests/lint. Root cause and Spotify recovery remain unverified; see [audio diagnostic record](phase-2/AUDIO_AND_BACKGROUND.md).

Spotify diagnostic logs showed ALAC/352 negotiation, granted focus, allowed audio packets and output-init failure. Build 13 corrects missing ARM ALAC byte-order configuration, adds a pre-allocation wire/configuration guard and independent native assertions. Build, focused tests, native checks and lint pass; installed preferences were preserved. Spotify audible recovery awaits a fresh user connection. See the [ALAC correction record](phase-2/AUDIO_AND_BACKGROUND.md).

The user subsequently confirmed “all works fine” after build 13. Spotify AirPlay audio is accepted by user report alongside the previously confirmed iPad video/pictures, iPad mirroring and Mac extended desktop. This supersedes the pending Spotify-recovery status above; broader compatibility, long-session behavior and ADB closure are not claimed.

## Combined Bardi/AirPlay merge verification

After the Bardi branch merged into main (`3774cbb`), main was merged into `plan/phase-2-airplay` to resolve the remaining integration conflict. Only the app version declaration and appended verification history conflicted; both implementation trees merged without content conflicts. The combined candidate is **0.6.1-integration-preview**, versionCode **14**, greater than both installed/tested candidate codes 10 and 13. Both Bardi and AirPlay acceptance histories are retained.

The combined APK builds; all **105 JVM tests** pass with zero failures/errors; native ALAC wire/configuration, round-trip and media helper checks pass. Lint has zero errors and the existing ChromeOS ABI warning. Bardi color behavior and AirPlay ALAC byte-order corrections are both present. No new TV installation, lamp write or casting session was performed for merge verification; this is local integration verification, not a new hardware acceptance result.

## iPhone/iPad remote and alert2 candidate

Branch `feature/ios-remote-notifications` adds a native iPhone/iPad app, Google TV Remote v2 pairing/control, a PIN-authenticated `alert`/`alert2` sender, and an urgent TV overlay for `alert2`. The normal alert path retains its compact presentation. The urgent path shares the same admission, FIFO queue, expiry, source filter and privacy settings; it supports manual Dismiss/Back and timed closure. Android versionCode 16 (`0.8.0-companion-preview`) is reserved for this candidate.

Local verification on 2026-10-30: Android debug APK build and lint pass; 111 Android JVM tests report zero failures/errors across the repository's test result files. `TVCompanionKit` passes 11 Swift tests, and the iOS app builds for a generic iOS Simulator with signing disabled. `git diff --check` passes. The iOS GitHub Actions workflow is added but has not run on GitHub. No TV or Apple device was connected or installed during this work. Physical pairing, discovery, remote keys, TLS certificate behavior, HTTP delivery and overlay appearance remain pending device acceptance.

## 0.7.0 sender takeover and app-video candidate

On `investigate/airplay-session-video-flow`, versionCode 15 builds with 111 passing JVM tests, passing native helpers, a verified source-patch round-trip and lint with zero errors plus the existing ChromeOS ABI warning. The candidate adds ordered sender replacement, a platform HLS player within the native integration, control protocol v5 and typed unsupported/protected-video feedback. User explicitly requested no installation: no TV connection, deployment, casting or lamp command was performed. Android player behavior, actual handoff, YouTube and Crunchyroll compatibility remain unverified; see the [device test matrix](phase-2/SESSION_AND_APP_VIDEO_REVIEW.md).

## Phase 3 integration with merged Phase 2

The `feature/ios-remote-notifications` branch was updated from `main` after AirPlay PR #5 merged. Both verification histories were retained. Android versionCode 20 (`0.8.0-phase3-preview`) exceeds the installed Phase 2 build 19. On 2026-11-01, the combined Android APK build, focused notification/AirPlay/runtime/native unit tests and lint passed; the Swift kit passed 11 tests and the iOS simulator app built with signing disabled. This is local integration verification. No Android or Apple device installation, pairing, notification delivery, or combined-feature physical test was performed for Phase 3.

## Phase 3 opt-in reboot startup

VersionCode 21 (`0.8.1-phase3-boot-preview`) adds separate saved TV choices for notification and AirPlay startup after `BOOT_COMPLETED`. AirPlay restores Background listening, Keep session connected, and Background audio without restoring an old sender session or requesting media playback while idle. The merged manifest includes `RECEIVE_BOOT_COMPLETED` and a private boot receiver. Focused notification/AirPlay/runtime tests, the Android debug APK build and lint pass locally; no Phase 3 APK has been installed and no physical reboot test has been performed.

The user later requested continuation. ADB reconnected to the home TV, whose installed app was versionCode 19. The versionCode 21 debug APK installed successfully as an update and the package reported `0.8.1-phase3-boot-preview`. No preference reset or device reboot was performed. The two new boot choices remain off until selected on the TV; actual boot restoration and combined-feature behavior await user review.

## Phase 3 iOS TV keyboard

On 2026-11-04, the iPhone/iPad remote gained an explicit text entry field and TV Delete/Enter keys. Remote v2 IME support is negotiated, and text messages carry the latest field counters reported by the TV. The Swift protocol kit passed 12 tests, the iOS Simulator app built with signing disabled, and `git diff --check` passed. No Apple device or live TV text field was tested for this addition; TV app compatibility remains a device check.

## Phase 3 closeout — 2026-11-06

The Phase 3 iOS remote redesign merged through PR #7. The user reports that **all requested closeout checks passed** on the home setup: iPhone/iPad pairing and alternating use, reconnect after background/relaunch/Wi-Fi return/TV reboot, remote keys and focused keyboard input; normal `alert` and urgent `alert2` delivery with separate PIN and TV presentation/dismissal/expiry/ordering/privacy; notification and AirPlay opt-in reboot startup plus lamp/notification/AirPlay regressions; and voice command with microphone cleanup after Stop, backgrounding and interruption. This supersedes earlier pending-device notes for those tested flows.

Phase 3 is accepted for the tested setup by user report. Device OS, TV firmware and Remote Service versions, logs and screenshots were not supplied, and the development agent did not install or run UI/device tests. Older-receiver rejection, TV wake behavior, unusual TLS/certificate failures, wider device compatibility and store-release readiness remain outside this acceptance. Phase 2 reverse-sender takeover and iPad YouTube compatibility retain their separate scope.

## AirPlay Ambient mode — 2026-11-17

Branch `docs/airplay-visualizer-assessment`, code through `5953481`. Design: [Ambient spec](superpowers/specs/2026-11-17-airplay-ambient-design.md); see [Ambient mode](ARCHITECTURE.md#ambient-mode).

Run locally for this entry with JDK 17 (compileSdk 34). The native host check ran fresh; the Gradle build/test run completed with most tasks up to date, their inputs unchanged since their last passing run in this session:

```sh
python3 scripts/check-airplay-native.py && cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :integrations:airplay-runtime:testDebugUnitTest :integrations:uxplay-native:testDebugUnitTest
```

- Native checks passed, including the ambient host check, which measured when the feature landed 16 kick onsets on an 8 s 118 BPM synthetic track, 1 onset on a sustained tone and 4 end-to-end beats.
- `BUILD SUCCESSFUL` (221 tasks, 15 executed, the rest up to date). The test result files contain 116 results with zero failures/errors. The only warning is the known armeabi-v7a-only native library notice.

Device checks on the project's MT5867 / Mali-G31 Android 14 TV (1280x720), run over adb with the user driving the sender:

- **CP1, PASS:** Ambient button hidden with no sender and shown with one; aurora renders with `GL_RED_BITS=8` and no fallback to the built-in look; frame interval mean 33.2-33.3 ms (max 38.7-41.6 ms), draw mean 1.1-1.9 ms, so **720p is kept and no 540p fallback is needed**; 20 open/close cycles with music playing left audio and the `:airplay` process unchanged; 5 open/close cycles with "Keep session connected" off kept the session; Screen Mirroring while Ambient was open closed Ambient and showed the mirrored video (user-confirmed).
- **CP3, partial:** aurora on live music held 30 fps (33.3 ms mean, draw about 1.2 ms) with no crash or audio failure logged over 50 s. The user said the visual is "a bit off on upbeat songs, okay for now", so beat sync on fast tempos is not tuned and **`AMBIENT_SYNC_OFFSET_MS` stays 0**.
  - Since the final fix wave the code default sync offset is −25 ms (AmbientMotion.SYNC_OFFSET_MS); not yet checked on device.
- **Look picker, NOT TESTED:** D-pad LEFT/RIGHT look switching (added 2026-11-17 by user request) is covered by JVM and glslc checks only; on-device switching, the label and the remembered choice have not been run on the TV.
- **CP3, NOT TESTED:** pause, low volume, stop/restart and sender takeover with Ambient open.
- **Kept looks:** `ripples`, `dusk` and `strings` were later loaded and measured on the TV; see [Ambient automatic 540p fallback](#ambient-automatic-540p-fallback--2026-11-17). The default remains `aurora`.
- **No-Ambient regression, NOT TESTED (deferred by user):** Screen Mirroring video, background listening, sender takeover and HLS app video with Ambient never opened.

## Ambient automatic 540p fallback — 2026-11-17

Debug builds now log GPU time per look (`gpu mean/max` in the 10 s `TVCAmbient` stats line, via `GL_EXT_disjoint_timer_query`). On the MT5867 / Mali-G31 TV, SurfaceFlinger composites the Ambient and video layers on the same GPU as the look's draw, so a look's own GPU time must stay at about 14 ms or less to hold 30 fps. Measured:

| Look | Size | GPU draw | Result |
|---|---|---|---|
| strings | 1280x720 | 20.7 ms | ~22 fps (miss) |
| dusk | 1280x720 | 15.7 ms | 34 ms interval (just misses) |
| strings | 960x540 | 13.3 ms | 30 fps |
| dusk | 960x540 | 10.1 ms | 30 fps |
| aurora, ripples | 1280x720 | ~1 ms | 30 fps |

The user approved how `strings` looks at 540p ("looks fine"). Ambient now opens every look at full size and falls back automatically: if two consecutive 3 s windows each have a mean frame interval over 33.9 ms (first 1 s after a look loads or the surface is created ignored; healthy looks measured 33.33-33.39 ms over 10 s, `dusk` 34.05 ms, so a single stall cannot trigger it), the surface is set to 960x540 and the look is remembered for the process lifetime, so switching back to it goes straight to 540p. Detection is generic, never keyed to look names. Debug builds log `look=<name> fallback=960x540 interval=<mean>us`. Covered by `AmbientDownscaleTest` (JVM); **the automatic fallback itself has not yet been run on the TV**.

## Quick Controls Phase 1 (headless entry points) — 2026-11-22

Branch `feature/quick-controls`. Nothing was installed on the TV.

```sh
cd android && ./gradlew clean test assembleDebug
```

- `BUILD SUCCESSFUL`; 331 JVM test results (debug + release variants), 0 failures, 0 errors, 2 skipped (pre-existing). New tests: `LightPresetsTest` (4), `LightControllerTest` (+7, 14 total), `AmbilightFacadeTest` (2), `AmbientShortcutTest` (3), `AirPlayWireTest` (bit 128 → `ambientShowing`).
- No feature module depends on another feature (`grep "project(':features:"` in feature/integration build files: no matches).
- iOS packages unchanged (baseline: TVCompanionKit 15/15, TVCompanionAudio 2/2).

Owner device checks (not yet run):

- [x] 1. **PASS (2026-11-22, Claude over adb):** Light screen reads the lamp ("Light is on · Confirmed"); Turn off → confirmed off, Turn on → confirmed on, restored to 10 % white; leave and re-enter keeps the state; presets render. The first read after install returned unavailable once, then succeeded.
- [x] 2. **PASS:** Start opens the capture prompt via `ConsentActivity` (Stop stays enabled behind it); approve → "Synchronizing · Controller: WLED"; Stop → Ready; home card "Ready". Optional prompt skip (`PROJECT_MEDIA allow`) not tried: currently `ignore`.
- [x] 3. **Recorded:** `SYSTEM_ALERT_WINDOW: allow` (Android 14). Record the overlay permission: `adb shell appops get id.poncol.tvcompanion SYSTEM_ALERT_WINDOW`. A real background start of `ConsentActivity` and Ambient is the first device check of the Phase 2 plan.
- [x] 4. **PASS (owner, 2026-11-22):** with AirPlay music, Ambient opens and closes, looks switch, Back closes.
- [x] 5. **PASS:** Notifications "Listening on port 8766", start after reboot On, overlay allowed; AirPlay "Listening for a connection", background listening On; both services running; no crash log.

## Quick Controls iOS tab — 2026-11-22

Built before the Android control listener (owner's order), so the tab can't reach a real TV yet: against the TV it shows "Can't reach the TV controls".

- `swift test` in `ios/TVCompanionKit`: 20/20 (new: `ControlWire` encoding, pairing proof, full/unknown/oversized/wrong-version responses).
- `xcodebuild … -destination 'generic/platform=iOS Simulator' build`: BUILD SUCCEEDED.
- Not yet checked: the tab's look on a device or simulator, and everything end-to-end (pairing, each control, `would_end_playback` confirmation, TV unreachable/forgotten). These are Phase 2 device checks.

## Quick Controls end to end — 2026-11-22

TV: Skyworth (Android 14) at the profile address; iPhone 12 Pro Max running the Controls tab; a temporary Mac probe client (mutual TLS, removed after testing) used to read exact replies.

- **Pairing:** the first attempt failed. The AndroidKeyStore key lacked the raw-RSA purpose Conscrypt uses during the TLS handshake (`INCOMPATIBLE_PADDING_MODE`). Fixed with `PURPOSE_DECRYPT` + `ENCRYPTION_PADDING_NONE` and a new key alias. After the fix: TLS 1.2 ECDHE-RSA; an unpaired client gets `denied`; the iPhone paired with the TV code; the TV shows ✓ and stores the client.
- **Owner on iPhone, TVCompanion in the background:**
  - Notifications off/on: pass.
  - Light on/off and presets: pass.
  - AirPlay "Available in background": failed, then passed after a fix. The passive AirPlay binding died when AirPlay stopped and never reconnected; it now re-arms on `onBindingDied`. Probe: off → `stopped`, on → `ready, available`.
  - Ambient visualizer with AirPlay music: failed (same cause), then passed after the fix (owner).
- **Ambilight:**
  - Without `PROJECT_MEDIA`, a remote start shows the capture prompt on the TV.
  - The owner ran `adb shell appops set id.poncol.tvcompanion PROJECT_MEDIA allow`. A remote start then skips the prompt (`ConsentActivity` → `MediaProjectionPermissionActivity` auto-approved, `BAL_ALLOW_VISIBLE_WINDOW`).
  - The test run ended in `error` because the WLED ESP32 was powered off (`WLED /json/info failed: SocketTimeoutException`). That is the correct report, not a fault.

## Ambient looks: Edge Orbit and Chromatic Flow — 2026-11-22

Branch `feature/ambient-ambilight-presets` (started by Codex, finished here; `main` merged in). Debug builds installed on the Skyworth Android 14 TV; owner judged the looks with AirPlay music.

- **Tests:** `:integrations:airplay-runtime:testDebugUnitTest`, `:core:ambilight-engine:test`, `:app:testDebugUnitTest`, `:app:assembleDebug` pass. `AmbientShadersTest` 12/12 with `AMBIENT_GLSLC` set to the NDK `glslc` (no skips). The compiler caught a reserved word (`half`) and the phase test caught a fractional multiplier during the iterations. iOS `TVCompanionKit` 26/26.
- **Edge Orbit (accepted "good enough for now"):**
  - Iteration 1 (rainbow edge with a comet per beat) and iteration 2 (the original two ribbons with a positional beat kick) were rejected: too busy, then "clunky".
  - Final version: a rounded orbit path (radius 0.30, no corner seams), two head-and-tail streaks on a bold cyan/violet/magenta/amber path, and beats as waves running down each tail. 5 laps per clock cycle (owner asked for it faster). Light ends 0.32 in from the edge with a 0.12 soft fade; the dark centre exits early.
  - Performance: the first streak version measured 45 ms per frame (22 fps, GPU 20.7 ms). After the early exit and fewer palette lookups it is expected at 30 fps; the final build was not re-measured.
- **Chromatic Flow:**
  - A beat-flash version was withdrawn as a photosensitivity risk (per-beat hue steps and an edge flash).
  - The calm version measured 30 fps (GPU 7.5 ms) but was not responsive enough.
  - Final version: four fields tied to bass (pushes outward), mids (breathes), treble (shimmers) and an anchor, using smoothed band levels. Colours are blended rather than added (no white fog); per-field brightness swing is capped near 25%. Owner: "okay for now"; not re-measured.
- **Also fixed:** both new looks used fractional multiples of the phase clocks for hue, which made the colour jump at each clock wrap. They now drift through the palette once per slow-clock cycle (about 3 minutes).
- **Open:**
  - The 540p automatic fallback did **not** trigger while Edge Orbit ran at 22 fps. It may have been broken by the queued-look change (`2a4f55b`) and needs a look.
  - The Codex plan's Task 4 (long soak, CPU/PSS with Ambilight off and on, reduced-motion pass) is not done.
- **Bounce (added; Ripples removed):**
  - The owner's idea: a ball bounces on a black screen and lights the wall it hits, which shows off Ambilight. The path is an analytic billiard (17/23 hits per half clock cycle), so there is no new renderer state. The ball speeds up with louder music.
  - Iterations: the first version measured 14.7–16.3 ms GPU (28–29 fps) and showed a hard-edged dark box where the early exit began. Per-frame maths moved to `bounce.vert` (4 runs per frame), and the splashes and halo now fade gradually into the black. A solid-ball experiment was rejected; the final version uses a glowing ball about a third smaller (radius 0.023). Owner: "it's great". The final build has not been re-measured.
  - A faint rim keeps Ambilight's letterbox rule (dark rows held 15 frames) and blank rule (≤1% edge samples above 12) from misreading the black frame.
  - Ripples is removed; a saved `ripples` choice falls back to Aurora.

