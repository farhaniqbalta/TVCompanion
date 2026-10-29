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
