# Current architecture — Phase 1 assessment

Date: 2026-10-28. Source baseline: `555d4be`. Review branch: `review/phase-1-architecture`.

This document preserves the pre-refactor baseline. For the approved changes see [implementation changelog](IMPLEMENTATION_CHANGELOG.md).

This is a code-level assessment against the user-provided `TVCOMPANION_ARCHITECTURE_HANDOFF.md` in the parent workspace. That handoff is not a repository dependency. Findings below are observed in source; failure scenarios not exercised are explicitly described as risks. No implementation changes are included in this review.

## Verdict

Keep the existing architecture and use a targeted refactor. There are six Gradle modules, an acyclic module dependency graph, a pure Kotlin/JDK domain/application module, working ports for light control and notification settings, and explicit screen/service ownership. Neither a new plugin framework nor a module rename is justified.

## Build and dependency map

Observed in [`settings.gradle`](../../android/settings.gradle), the root build and each module's build file:

```text
app -> features:smart-home, features:notifications,
       integrations:tuya-lan, core:designsystem, core:domain
features:smart-home -> core:domain, core:designsystem
features:notifications -> core:domain, core:designsystem
integrations:tuya-lan -> core:domain
core:designsystem -> Android platform
core:domain -> Kotlin/JDK only
```

Android libraries and the application use Kotlin 1.9.24, AGP 8.5.2, SDK 34, minSdk 26 and JVM target 17. The domain is a Kotlin JVM module using JDK 17. No AIDL, JNI/native media implementation, boot receiver, wake lock or scheduled background polling was found in the application sources. Module separation does not create process isolation: existing features run in the application process.

## Concrete implementation map

| Responsibility | Actual files and behavior |
| --- | --- |
| Composition/navigation | [`MainActivity.kt`](../../android/app/src/main/java/id/poncol/tvcompanion/app/MainActivity.kt), particularly lines 56–74: constructs the concrete Tuya gateway and light controller, injects dispatch through a main-thread Handler, closes the controller on leaving/backgrounding the screen, and launches notification settings |
| Light presentation | [`LightScreen.kt`](../../android/features/smart-home/src/main/java/id/poncol/tvcompanion/smarthome/LightScreen.kt): renders normalized state, issues explicit power/brightness commands and restores useful focus; does not import Tuya or JSON |
| Light models/port | [`Light.kt`](../../android/core/domain/src/main/java/id/poncol/tvcompanion/domain/Light.kt): `LightState`, `LightCommand` and blocking `LightGateway` |
| Light application state | [`LightController.kt`](../../android/core/domain/src/main/java/id/poncol/tvcompanion/application/LightController.kt): one worker, one outstanding operation, UI/event-thread entry points, no automatic write retry, display messages and observer |
| Tuya adapter | [`TuyaLightGateway.kt`](../../android/integrations/tuya-lan/src/main/java/id/poncol/tvcompanion/tuya/TuyaLightGateway.kt): loads credentials, normalizes DPS, reads before writing, checks white-mode capability, waits 600ms, reconnects and verifies exact DPS readback |
| Tuya protocol | [`Tuya35.java`](../../android/integrations/tuya-lan/src/main/java/id/poncol/tvcompanion/tuya/Tuya35.java): authenticated Tuya 3.5 framing/session negotiation, RFC1918 address validation, 4s connection/read timeout configuration, 12s per-session deadline checks and bounded frame sizes |
| Credential storage | [`Credentials.java`](../../android/integrations/tuya-lan/src/main/java/id/poncol/tvcompanion/tuya/Credentials.java): Android Keystore AES-GCM, atomic encrypted writes and deletion of the private one-time provisioning file; application backup/transfer exclusions are in the manifest/resources |
| Notification application policy | [`NotificationController.kt`](../../android/core/domain/src/main/java/id/poncol/tvcompanion/application/NotificationController.kt) and [`NotificationPorts.kt`](../../android/core/domain/src/main/java/id/poncol/tvcompanion/domain/NotificationPorts.kt): start permission gate, pause, settings changes and preview policy behind preferences/runtime ports |
| Notification settings/assembly | [`NotificationSettingsActivity.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/presentation/NotificationSettingsActivity.kt): creates Android adapters at its independent Activity entry point, owns permission UI, category navigation, transient dialogs and view bindings |
| Runtime/storage adapters | [`AndroidNotificationRuntime.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/infrastructure/AndroidNotificationRuntime.kt), [`ReceiverSettings.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/infrastructure/ReceiverSettings.kt), [`PairingStore.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/infrastructure/PairingStore.kt): service commands, process-local status, local address display, private preferences and six-digit PIN |
| Receiver composition/lifecycle | [`ReceiverService.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/infrastructure/ReceiverService.kt): foreground lifecycle, HTTP setup, event submission, queue drain, overlay rendering, dismissal, status and shutdown |
| HTTP transport/admission | [`LocalServer.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/infrastructure/LocalServer.kt) and [`EventGate.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/infrastructure/EventGate.kt): LAN HTTP authentication/framing, JSON validation, source policy, TTL, deduplication, acceptance rate and queue admission |
| Pure event/queue | [`NotificationEvent.kt`](../../android/core/domain/src/main/java/id/poncol/tvcompanion/domain/NotificationEvent.kt): transient event model and synchronized FIFO, ten waiting events maximum |
| Overlay presentation | [`NotificationCard.kt`](../../android/features/notifications/src/main/java/id/poncol/tvcompanion/notifications/presentation/NotificationCard.kt): renderer plus source display themes/demo payloads, shared by settings and the service |
| Design system | [`TvUi.kt`](../../android/core/designsystem/src/main/java/id/poncol/tvcompanion/designsystem/TvUi.kt): shared native TV presentation, vector identity and focus states; contains no network/device behavior |

The receiver importing its renderer is an outer-layer composition choice within one Android feature, not a domain dependency violation. Notification Activity assembly is another valid composition entry point; moving every constructor into `app` would not by itself improve dependency direction.

## Execution, resource and security behavior

Light operations run on the controller's executor. Credentials and protocol work are outside the UI thread. Fresh status precedes a write, and a separate session confirms the requested raw value. Failure clears actionable UI state; the controller does not retry writes. Each session is closed by `use` when its operation returns or throws.

Notifications are opt-in. `START_NOT_STICKY` means the code does not request automatic restart. A single listener blocks in accept, permits one active client, caps headers at 8192 bytes and bodies at 4096, and applies an input deadline. Pause closes the listening socket and active connection. The queue holds at most ten waiting events, remembered IDs at most 256, and accepted messages are limited to twelve per eight seconds. UI status refreshes are one-shot callbacks, not periodic polling. Overlay visibility is capped by duration/expiry with a short optional fade.

The receiver is not exported; the app entry Activity is exported for normal TV launch. No light-command HTTP endpoint exists. Existing LAN notification authentication is a six-digit bearer PIN over unencrypted HTTP. It is a documented prototype trust model, not a suitable default for adding physical-device commands.

## Findings ranked by impact

| ID | Priority | Observed evidence | Impact and recommendation |
| --- | --- | --- | --- |
| F1 | High: gate before exposing client commands | `LocalServer.kt:18,75–94`, `PairingStore.kt:5–11`, notification v1 contract: wildcard LAN listener, plaintext bearer PIN; wrong-PIN requests return before `EventGate` acceptance throttling | Do not attach physical control to this listener by reusing its PIN. Document a separate command contract and make transport encryption, device authorization, failed-auth throttling and replay/idempotency policy a prerequisite for exposure. No new command endpoint is present today. |
| F2 | Medium: concrete lifecycle/resource risk | `Tuya35.java:82–90` checks remaining time once, then calls `readFully` twice. `LightController.kt:44` requests worker interruption but does not close the active protocol socket; `MainActivity.kt:56–69` can create a new worker on return | A peer sending fragments below the socket's idle timeout can keep a read in progress past the intended session deadline. Closing a screen suppresses callbacks but does not directly abort the active socket. Fix deadline enforcement and cancellation together, with fake-I/O tests. This scenario was identified in source, not reproduced against the lamp. |
| F3 | Medium: lifecycle testability and state consistency | `ReceiverService.kt:20–24,32–54,69–119` combines mutable static flags, English status, transport, queue scheduling and WindowManager; shutdown tests `state.startsWith("Listening")`. Runtime reads these globals; settings schedule 300/700ms refreshes | Introduce one typed receiver state/snapshot and a small testable lifecycle/session coordinator within existing modules. Keep Android foreground/WindowManager mechanics at the edge. Make the owner's state transitions explicit before adding another capability lifecycle. |
| F4 | Medium: future client-command boundary | `LightController.kt:19–25,29–42` requires caller-thread confinement and prior UI state, silently ignores busy requests, returns Unit and catches all failures into English text | Keep this as a screen state holder. A network request must eventually call an application operation with an explicit outcome and execution owner, not instantiate an Activity/controller or parse UI strings. Document that boundary now; implement it with the first approved authenticated command consumer. |
| F5 | Medium: regression coverage gaps | Eight protocol tests cover byte framing/crypto/address rules; controller tests use a fake gateway. HTTP/queue tests are not Android service or real gateway orchestration tests | There is no independent automated proof of gateway pre-read/write/readback ordering, cancellation of active socket I/O, failed service start, overlay failure or service recreation. Add targeted tests at seams exercised by each approved refactor. Existing test passes must not be presented as hardware proof. |
| F6 | Low: documentation/product alignment | README/architecture/Apple placeholder describe an ecosystem and potential direct client/device access; the handoff now makes Google TV the primary hub | Clarify TV-host/Apple-client roles, while retaining direct Mac-to-receiver media and any separately validated platform remote-service connection. Do not equate independent builds with peer hubs. |

Display strings in the application ports/controller are a small presentation coupling, not an Android import violation. Typed results/status address this when those seams are changed; a broad localization or class renaming exercise is unnecessary.

## Evidence and limitations

A fresh local run with `--rerun-tasks` succeeded: assembly, all 33 tests and app lint including Android libraries; zero lint findings. Source import/dependency inspection found no Android/JSON/vendor imports in domain/application source. See [verification](../VERIFICATION.md#phase-1-assessment-baseline--2026-10-28).

No device commands, deployment, credential reads, socket-failure reproduction, extended-display experiment, native dependency audit or benchmark occurred during this assessment. Historical user-confirmed functionality remains valid historical evidence. GitHub Actions is separately postponed and was not changed.
