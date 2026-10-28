# AirPlay receiver control — Phase 2 design boundary

Status: design only; no AIDL, Binder, JNI, native dependency or receiver implementation exists. This document supersedes its Phase 1 external-APK proposal under [decision 13](../docs/DECISIONS.md#phase-2-packaging-correction--2026-10-28). The filename is retained for existing links; “external” no longer describes production packaging.

## Ownership and packaging

Production ships one TVCompanion APK. Its private `:airplay` process owns AirPlay discovery/networking, native sessions, decoding, synchronization and rendering. Main-process UI/application orchestration controls it through a focused Android adapter. Mac media goes directly to the receiver networking stack. No frames, decoded audio, surfaces, buffers or native pipeline handles travel over the control contract.

Independent native development/testing and an optional standalone development-only harness are allowed. Users do not install a second production receiver app. A separate Android process is a lifecycle/failure boundary, not a resource or licensing guarantee.

## Proposed focused port

Add a pure application port only when an actual receiver consumer needs it. Conceptual responsibilities, not final method/type names:

- Availability: supported, unavailable on this build/device, initialization failed or disconnected.
- State: stopped, starting, discoverable, session active, stopping or failed; advertise ready only after receiver initialization succeeds.
- Explicit start/stop requests with bounded asynchronous acknowledgement. Acceptance does not prove discovery or visible playback.
- Scoped status observation with deterministic unsubscription and normalized failures.

Keep Android Binder/AIDL, JNI, surfaces and native types outside `core:domain`. Do not reuse notification `ReceiverSnapshot` or introduce a generic capability API. Pairing secrets and notification content are excluded from ordinary status callbacks.

## Android adapter and service

Use explicit same-application binding to a non-exported service in `:airplay`; no external package discovery or independently signed provider is required. Keep a small versioned contract for maintainability, but independent APK update compatibility is not a production requirement. Never expose the service merely to simplify a harness.

The main-process adapter owns binding/death handling, bounded pending requests, callback threading, stale-callback rejection and cleanup. Do not perform blocking native work on Binder threads. The receiver service serializes lifecycle transitions and owns native initialization, session shutdown and resource release. Repeated stop and start-during-stop behavior must be defined and tested. Process death cancels pending requests and invalidates cached state; recovery is explicit or narrowly bounded, never an infinite reconnect loop.

The proposed private playback Activity/surface also lives in `:airplay`. Surface creation/destruction is local to the renderer, not an extension of the control IPC. Validate foreground service type/permissions, user-initiated launch, background restrictions, audio focus, surface recreation and shutdown before finalizing the manifest. Native loading and initializers must not start notifications or Tuya in the receiver process, or UxPlay in the main process.

No dashboard-triggered receiver startup, boot receiver, permanent native worker or polling is required. Desired discoverable lifetime must be decided explicitly; stopping AirPlay releases sockets, discovery registration, decoder/audio resources and callbacks. Android may retain a cached process; do not use process killing as normal cleanup.

## Gates and tests

See the [Phase 2 plan](../docs/phase-2/PLAN.md). True extended desktop, Android portability, target-TV media behavior and licensing remain unresolved. A standalone harness is optional development tooling, not a mandatory production dependency.

When implemented, test initialization failure, process death, delayed replies, stale callbacks, unsubscribe/unbind, repeated start/stop, surface loss and complete native cleanup. Verify existing notification and lamp behavior independently of receiver availability. These are planned checks, not executed evidence.

## Interchangeable implementation proposal

The [receiver boundary review](../docs/phase-2/RECEIVER_BOUNDARY_REVIEW.md) defines proposed neutral models, request/observation semantics and distinct adapter-versus-engine cleanup ownership. It refines the module plan into an Android runtime host and a replaceable engine selected by app composition at build time. No runtime plugin mechanism is proposed. See [license compliance](../docs/LICENSE_COMPLIANCE.md) for why implementation separation does not exempt a combined release from GPL obligations.
