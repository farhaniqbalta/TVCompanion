# Phase 1 preparation and migration plan

> Historical Phase 1 record. Its separate-production-receiver APK assumptions are superseded by [decision 13](../DECISIONS.md#phase-2-packaging-correction--2026-10-28) and the [Phase 2 plan](../phase-2/PLAN.md). The production target is one TVCompanion APK with a dedicated `:airplay` process. Original findings and implementation history below are preserved.

Branch: `review/phase-1-architecture`, based on `555d4be`. Status: **Phase 1 implemented, locally verified, installed and user-accepted for lamp/notification functionality**.

The handoff asks for proposed changes, affected files, rationale, regression risks and a test plan before implementation. The user requested preparation/review and a separate branch before any change. The assessment commit `8b975de` contained documentation only. The user subsequently approved implementation on this branch. See [implementation changelog](IMPLEMENTATION_CHANGELOG.md) for the separate product-role, Tuya and notification commits. Main, CI configuration and the TV are unchanged by this implementation.

## Preparation completed

- Read the handoff, repository README/architecture/decisions/design/verification and actual code/build/test sources.
- Created the review branch before writing these documents.
- Mapped module dependencies and entry points in [current architecture](CURRENT_ARCHITECTURE.md).
- Defined a minimal proposal in [target architecture](TARGET_ARCHITECTURE.md), with observed findings separated from future work.
- Re-executed all baseline assembly/tests/lint tasks. All 33 tests pass; zero lint findings.

## Proposed small implementation steps

| Step | Scope / affected files | Rationale and risk | Test/acceptance evidence | Rollback |
| --- | --- | --- | --- | --- |
| P1.1 — Align role documentation | `README.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `ios/README.md` | Clarify TV host, Apple clients and independent receiver. Risk: accidentally imply all traffic traverses the hub or that Mac/remote support already exists. Preserve explicit implementation status. | Review diagrams against actual direct media/control paths and current-state map. No runtime change. | Revert only this documentation commit. |
| P1.2 — Enforce real Tuya I/O bounds and cancellation | `integrations/tuya-lan/.../Tuya35.java`, `TuyaLightGateway.kt`; possibly a narrow cancellation extension at `core/domain/.../Light.kt` / `LightController.kt` and composition in `app/.../MainActivity.kt` **only if required** | The session deadline currently does not cover every partial read and screen-close interruption does not close active socket I/O. Risk: aborting after a write, changing successful timing, or exposing transport details to the domain. Preserve authentication, DPS mapping, timeouts/profile semantics and unconfirmed outcomes. | Add fake-I/O/socket tests for slow fragmented headers/bodies, session deadline expiry, active cancellation, resource close and late result suppression. Test gateway pre-read -> write -> readback, unsupported brightness and readback mismatch with synthetic state. Keep all eight protocol fixtures and existing controller cases passing. | Separate focused commit; revert adapter/cancellation change together. No preference/key/package migration. |
| P1.3 — Make notification lifecycle state explicit and testable | `core/domain/.../NotificationPorts.kt`, possibly a small typed status model; `features/notifications/.../ReceiverService.kt`, `AndroidNotificationRuntime.kt`, and a small session coordinator at the feature/application boundary. Adapt `NotificationController.kt` / settings binding only where typed status requires it. | Replace duplicated mutable flags and English-string state logic. Risk: leaked listener/overlay, changed foreground timing, false listening state, or reset private settings. Keep one Android service and existing ingress/renderer; extraction must demonstrate a test benefit. | Characterize start denied/fails/binds, stop with active connection, listener failure, overlay denied/fails, FIFO/expiry/muting and clean recreation. Verify typed status and actual lifetime agree, then verify current source aliases/dedup/queue/rate limits with existing tests. | Keep preference names/API/port stable; revert snapshot/coordinator and its edge adapters as one commit. No durable queue or new restart policy. |
| P1.4 — Finalize future integration contracts on paper | A focused client-command design under `contracts/`; receiver boundary in target architecture/ADRs. No endpoint, server, Binder, JNI or native dependency. | Avoid routing machine requests through UI state and keep media out of IPC. Risk: pretending a proposed interface is implemented, or adopting notification PIN auth for physical commands. | Explicit request/result/ownership/security decisions and examples using synthetic data. Map each approved operation to a real capability. Identify unresolved signing, lifecycle and encrypted-transport decisions. | Revert document only; no runtime change. |
| P1.5 — Verify and review baseline acceptance | Existing Gradle tasks, focused regression suites and `docs/VERIFICATION.md`; no UI restyle | Confirm the targeted changes preserve the currently working application. Risk: equating a fake with the real TV, or treating the postponed CI failure as a new app failure. | Fresh local assembly/test/lint, plus separately recorded real-device checks if authorized. Review new worker/queue lifetimes and pure-domain import direction. Report deviations. | Revert the offending step; keep a known-good APK/signing identity. Never uninstall to repair an update. |

File paths abbreviated with `...` above are mapped precisely in [CURRENT_ARCHITECTURE.md](CURRENT_ARCHITECTURE.md). Exact new class names should follow the smallest seam demonstrated by the first failing characterization test. No module rename is required.

P1.2 and P1.3 should be separate reviewable commits. For P1.3, first extract only enough lifecycle coordination for deterministic failure/cleanup tests; do not build an overlay framework or transport abstraction hierarchy. If tests demonstrate the current boundary already works, retain it and change only the state representation/defect actually exposed.

## Command boundary decision

A shared headless light-command executor is **not justified as an implementation in this review**: the only current consumer is the screen, and the authenticated Apple command consumer is not designed yet. Document an application-operation contract now. Extract shared execution/serialization when the first approved second consumer actually needs it. That avoids adding a permanent worker/server merely to satisfy a diagram.

The eventual network consumer must not call an Activity, parse a UI message, race a second device worker, or execute ambiguous toggle commands. Physical command authorization is a gate, not a later cosmetic hardening task. Existing notification v1 remains compatible.

## Validation to run after every relevant code step

From `android/`, with JDK 17 / SDK 34 configured:

```sh
./gradlew --no-daemon :app:assembleDebug :core:domain:test :integrations:tuya-lan:testDebugUnitTest :features:notifications:testDebugUnitTest :app:lintDebug
```

Check the dependency graph and domain source imports again. Characterization tests must exercise behavior, not mirror methods or UI layout. Readback success must be independent of optimistic send success. No test should access real credentials or switch the real lamp.

## Pending Android-only checks after approved implementation

These are instructions, **not checks executed in this assessment**. Use only the explicitly authorized TV endpoint; no network scan, benchmark or blind power-button injection.

1. Build/sign normally; `adb -s <authorized-endpoint> install -r <debug-apk>`. Stop if install/signature verification fails; never uninstall or change signing identity to force it.
2. `adb -s <authorized-endpoint> shell am start -W -n id.poncol.tvcompanion/id.poncol.tvcompanion.app.MainActivity`. Inspect the current page before each input. Navigate to light controls and confirm a read does not change power/brightness.
3. Using the actual remote, confirm focus/Back, perform one explicitly agreed power/brightness change, and observe the lamp and readback. Preserve/restore the user's starting state where requested. Mark visual response unverified without observation.
4. Background/reopen the light page and verify stale callbacks do not redraw it. Use a controlled synthetic/unreachable configuration or test build for I/O failure checks rather than altering unrelated Wi-Fi/router settings.
5. In notification settings, start/pause and revoke/regrant overlay permission through normal UI. Send synthetic notification v1 messages with fresh IDs through the existing documented endpoint; reuse an ID for duplicate response and use expired timestamps to check rejection. Obtain the PIN privately; never put it in logs, docs or command history.
6. Observe overlay order/dismissal and Back/navigation. Check pause during pending input, occupied-port startup failure and process recreation without claiming automatic restart or queue persistence. Prefer instrumentation for process-stop checks; do not kill unrelated apps/services.
7. Record exact outcomes; keep any captures private/redacted. Finish the workspace's ADB closure procedure with the user after testing. Closure is not assumed from disconnecting the client.

## Approval and stop points

The user approved and implementation completed P1.1–P1.3 on this branch. P1.4 contract documentation is also prepared. P1.4 is documentation of future consumers; implementing an endpoint needs its own concrete security/lifecycle decision. Phase 1 is not complete merely because these plans exist: the approved changes must pass verification and the baseline must be accepted.

AirPlay/UxPlay source research, receiver porting and AIDL implementation remain Phase 2. Independent extended-desktop feasibility must be demonstrated before significant Android porting, and a working standalone receiver must precede TVCompanion IPC. GitHub Actions remains postponed; no workflow edits or reruns are included here.
