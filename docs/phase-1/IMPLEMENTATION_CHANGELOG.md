# Phase 1 implementation — 2026-10-28

Branch: `review/phase-1-architecture`. Based on audit of `555d4be`; approved by the user after the assessment. The application/module identities, settings keys, notification JSON v1, port 8766 and TV layout are preserved. No workflow or media implementation is included.

## P1.1 — Product roles

README, architecture, decisions and Apple workspace now distinguish a Google TV host from Apple clients. The future independent receiver owns media. Direct protocol connections are not forced through a generic TV server.

Commit: `5c01b54`.

## P1.2 — Tuya I/O ownership

- `LightGateway` has an owned close lifecycle; `LightController.close()` interrupts its worker and closes the gateway, once, without retaining a screen observer.
- `TuyaLightGateway` registers a constructed session before connection negotiation, so close can abort active I/O. A session created concurrently with close is closed before connection. Late work cannot reconnect after the owner closes.
- `DeadlineInput` checks interruption and the absolute session deadline before and after every read fragment, recalculating the socket timeout from the remaining budget. Header and body use the same deadline.
- The existing raw DPS scale, authentication/session framing, private address validation, pre-read, 600ms settlement and exact readback remain. Cancellation after a write is not confirmation and does not retry it.
- A small session factory/pause seam supports synthetic tests without credentials, live sockets or changes to production address policy. Tests cover operation order, mismatch, capability denial, raw scale, close during negotiation/creation and cancellation after writing. Four fragmented-I/O/deadline tests supplement the eight original protocol fixtures.

Commit: `52b46a8`.

## P1.3 — Notification lifecycle

- `ReceiverSnapshot` is a pure immutable domain model: phase, optional issue, port and last-accepted timestamp. It never contains credentials or notification content.
- `ReceiverSession` owns one listener, admission/queue scheduling and presentation lifetime. `ReceiverService` remains the Android foreground-service owner; `AndroidNotificationOverlay` owns WindowManager and the existing 280dp/180ms card behavior.
- Listener allocation is recorded before start, so bind/start failure closes it. Failure status survives shutdown; normal pause explicitly transitions through stopping to paused.
- Admission and stop share a small lock. Dispatched work and stale/double dismissal callbacks cannot display a new card after stop or incorrectly dismiss its successor.
- A process-local immutable snapshot store replaces three mutable service globals. App-scoped status broadcasts are preserved and drive UI updates; the 300/700ms refresh timers are removed. Display copy/receipt formatting now lives in presentation resources/mapper.
- The existing queue, source/TTL/dedup/acceptance policies, preferences, manual start behavior and overlay dimensions/flags are retained. A request racing pause may receive a transient 503 `receiver_paused` if a response is still possible; pausing closes the connection and guarantees no presentation from late work.
- Fifteen session tests exercise typed transitions, permission denial, bind/platform-start failure, listener failure, stop/late callbacks, one visible card, FIFO, expiry/muting, overlay recovery, capacity, fresh-owner state and closing the real listener's incomplete loopback connection.

Commit: `e0bbff5`.

## P1.4 — Future boundaries

[Client-command design](../../contracts/client-commands-draft.md) defines future application results/ownership and a security gate separate from notifications. [External receiver control](../../contracts/external-receiver-control-draft.md) defines the future media/control boundary and provider-failure handling. Neither contract is an implemented API. No extra server, persistent worker, shared command executor, AIDL port or native media dependency is added.

## P1.5 — Verification and acceptance

See [verification](../VERIFICATION.md) for actual task/test results, warnings and pending device checks. The pre-refactor audit remains in `CURRENT_ARCHITECTURE.md` with its baseline clearly identified. Installation/native status and receiver/dashboard smoke checks are recorded. The user subsequently confirmed that lamp and notification functionality works and accepted the Phase 1 baseline on 2026-10-28. Dedicated offline/recovery and fault-scenario checks remain distinct pending validation. Phase 2 requires its separately scoped feasibility work.

## Rollback

Documentation, Tuya and notification changes have separate commits. Revert an owning step with its tests/edge adaptations rather than partially restoring a port. No durable data migration is required. Keep the existing signing identity and package; never uninstall to resolve a signature mismatch. Returning to the prior known-good source is a build/deployment decision, not an automatic TV operation.
