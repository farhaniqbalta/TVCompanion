# External media receiver control — Phase 2 design boundary

Status: documented in Phase 1; no AIDL, Binder, native dependency, receiver APK or domain port implementation is introduced here.

## Ownership

The standalone receiver APK owns AirPlay discovery/session networking, decoding, audio/video synchronization and TV rendering. It must be independently usable with TVCompanion absent. TVCompanion orchestrates optional availability, start/stop requests, status and UI entry points.

Mac AirPlay media goes directly to that APK. Media frames, decoded audio and native pipeline objects never flow through TVCompanion or the control IPC. A separate process is a resource/lifecycle boundary, not a guarantee of protocol compatibility or a licensing conclusion.

## Proposed focused port

Once a real provider exists, a pure domain port may expose:

- Availability/capabilities: absent, incompatible version, control denied, disconnected, supported.
- Receiver state: stopped/starting/available/session active/failed, with normalized failure categories.
- Start and stop requests with explicit asynchronous acknowledgement; accepted is not proof of discovery/rendering.
- Scoped status observation with deterministic unsubscription.

These are responsibilities, not final method/type names. No Android `IBinder`, AIDL interface, JNI handle, surface or buffer belongs in `core:domain`. Do not force notifications, lights and media into one generic capability interface.

## Future Android adapter

Use an explicitly identified provider, version negotiation, authenticated caller control and bounded IPC. Do not trust caller-supplied package names alone. Define the signing/permission arrangement deliberately: independently deployed APKs do not imply shared signing keys. Permission denial and provider absence/death must be normal outcomes, not app-start failures.

The adapter owns service binding/death handling, callback threading/cleanup, deadlines and any reconnect policy. Bind/subscription scope must be explicit. No unbounded retries, polling or automatic permanent receiver startup merely to show a tile. Provider launch/start rules must respect the actual platform foreground restrictions and user interaction model; finalize those after the standalone receiver works.

Private notification content and pairing secrets are not part of status/control IPC. Version incompatibility must degrade to an unavailable explanation rather than an undocumented fallback or bypass.

## Gates and tests

1. Prove true extended desktop with the current Mac and a compatible receiver; screen mirroring is insufficient.
2. Verify upstream source, Android portability/dependencies and distribution obligations at implementation time.
3. Build and validate an independent receiver APK before TVCompanion IPC work.
4. Only then implement the port/adapter and tests for missing/old/denied/crashed provider, delayed calls, stale callbacks, unsubscription and independent operation.

Phase 1 does not satisfy any AirPlay feasibility or hardware gate. Performance measurements for the media receiver belong to its later, separately scoped feasibility work; no benchmark is added to this refactor.
