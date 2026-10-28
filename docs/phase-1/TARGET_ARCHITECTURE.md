# Minimal target architecture — proposal for approval

Baseline: `555d4be`. This document proposes boundaries; it is not a statement that new command/IPC features exist.

## Product roles

Google TV is the host for TVCompanion orchestration, notification presentation and its supported smart-home use cases. iPhone/iPad/Mac are clients. Each Apple and Android build/release remains independent. CleanYourTV remains an optional external launcher.

The future standalone AirPlay-compatible receiver owns its discovery, network sessions, decoding and rendering. Mac media goes directly to it. TVCompanion is an optional control/status client of that APK; neither video frames nor decoded audio cross TVCompanion or its Binder control connection.

This host/client decision does not require routing every Apple protocol through one server. An Apple remote may use a verified stock TV remote service; an AirPlay sender talks to the separate receiver. Those transports still have explicit ownership.

## Preserve the existing module graph

```text
app / Android feature entry points: assembly + navigation + lifecycle
  -> features:smart-home: LightScreen + presentation state holder
  -> features:notifications: settings + Android receiver/overlay edges
  -> integrations:tuya-lan: protocol + private device configuration
  -> core:designsystem: native presentation

core:domain
  domain/: normalized models and focused application ports
  application/: use cases, controllers and typed outcomes
```

No module rename, plugin loader, registry, event bus, shared cross-platform runtime, database or dependency-injection framework is proposed. Keep the current package/application ID, signing arrangement, preference keys, port 8766, JSON aliases and overlays.

## Light behavior boundary

Keep `LightGateway` and normalized commands/state. Correct socket read deadlines and provide explicit cancellation for the active operation without exposing socket/JSON/Keystore objects to the screen or domain. Cancellation after a write must remain unconfirmed: it cannot prove the lamp was unchanged, and must not trigger an automatic retry.

The UI controller continues to own screen observation and focus-related state. Its screen lifetime must not become the lifetime of a future client request. Before a real second consumer is added, define the operation contract on paper:

- Explicit `SetPower(on)` / `SetBrightness(percent)` commands; never an ambiguous network toggle.
- A bounded request with a request ID, authorized device scope, deadline and cancellation owner.
- Typed outcomes: confirmed state, unsupported capability, unavailable/not configured, busy, cancelled, and unconfirmed/timeout where application is uncertain.
- Serialization shared by all consumers targeting the same device, with bounded admission and explicit busy responses. Separate UI/network queues must not race the same lamp.
- Adapter-owned pre-read, command validation and readback; no automatic write retries.

**Phase 1 recommendation:** document this contract, but do not create a headless executor/interface stack or network listener without an approved command consumer/security design. When that consumer is implemented, extract only the shared operation logic needed by it; retain the current screen controller as an adapter to those results.

## Notification lifecycle boundary

Within `core:domain`, use a small typed receiver snapshot/state model instead of English text plus mutable flags. The model distinguishes requested start, listening, stop, start failure, listener failure and presentation permission/failure. Accepted or queued is distinct from visible. Display copy stays in presentation.

Within `features:notifications`, one lifecycle/session owner should coordinate transport start/close and queue/presentation scheduling. Android's foreground service remains the platform owner; it must not become a generic capability host. Keep foreground declarations, WindowManager operations and Activity launch/permission UI at Android edges. Extract only enough coordination to exercise start failure, stop during active input, expiry, overlay failure, and restart in tests.

Expose state to the current settings screen through a scoped observer/callback or equivalent narrow interface. Subscribe/unsubscribe with screen lifecycle. No recurring status polling, permanent event bus or additional worker is needed. Screen departure must still release view references; receiver stop must clear transient content and current socket/overlay work.

Authentication/pairing metadata stays separate from ordinary status results. Never put PINs or private notification content in a broad status callback, log, or future IPC response.

## Future Apple-client commands — documented only

The notification JSON contract remains notifications-only. Do not overload `source`, body text or another field to invoke a lamp command. Do not reuse the six-digit plaintext HTTP PIN as authorization for physical control.

A future inbound command adapter will authenticate and authorize a client, decode/validate a versioned command envelope, and call an application operation. Its transport/security policy must cover encryption or a specifically reviewed secure local channel, scoped device authorization, failed-auth throttling, request expiry, replay protection and idempotency semantics after uncertain writes. Repeated requests must not silently turn an uncertain physical action into optimistic success.

Network ingress and device execution must have explicit bounded lifetimes outside Activities. Choosing that lifecycle is part of implementing the real endpoint, not justification to start a server or all-day light service during this assessment. The first contract should target only proven light capabilities, not a universal automation language.

## Future external receiver control — documented only

No AirPlay or Binder code belongs in Phase 1. Reserve a future focused `ExternalReceiverControl`-style domain port only once a real receiver is available. Its conceptual responsibilities are availability/capabilities, start/stop requests, and status observation. It exposes ordinary models, never Android binder objects or media buffers.

The future Android adapter maps an explicitly identified provider APK's versioned Binder/AIDL contract to that port. It owns connection/death handling, bounded calls, callback cleanup and reconnection policy. Missing provider, incompatible version, denied permission and dead process must be normal typed outcomes. Do not spin a reconnect loop or keep a receiver resident merely to show a dashboard tile.

Caller authorization/signing policy, actual Android foreground/session behavior, callback threading and exact APIs remain Phase 2 decisions. Do not assume independent APKs share signing keys. Any control permission must genuinely authenticate the intended caller; package-name strings alone are not sufficient proof. The receiver must remain independently usable and both projects independently buildable. Media remains direct Mac-to-receiver throughout.

## Deferred decisions and gates

- No new authenticated command endpoint until its transport and ownership proposal is accepted.
- No UxPlay port/native toolchain, AIDL files or receiver module until Phase 1 is accepted and receiver feasibility is established.
- True Mac extended desktop is a Phase 2 feasibility gate, not established by generic AirPlay compatibility or a mirrored picture.
- Upstream license/portability/compatibility research is deferred to Phase 2; this assessment makes no independent legal or compatibility claim.
- Existing GitHub Actions repair stays postponed. No Phase 1 benchmark or UI redesign is proposed.
