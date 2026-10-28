# Apple-client commands — design boundary, not an endpoint

Status: Phase 1 design only. No command listener, route, authorization service or shared headless executor is implemented. Existing notification v1 remains notifications-only. Google TV is the orchestration host; Apple devices are clients.

## First supported scope

Start with the already validated single light: explicit power and white-brightness commands. Do not implement an automation language, universal registry, discovery framework, scenes or native media commands as part of this boundary. Do not send raw Tuya DPS, local keys or addresses through a client API.

A future transport adapter will decode a versioned request, authenticate the client, authorize the requested device/action, validate expiry/replay policy, and submit a focused application operation. It must not invoke an Activity, create a `LightController`, inspect UI messages or call vendor code from a request/UI thread.

Illustrative decoded envelope (not an accepted HTTP payload):

```json
{
  "version": 1,
  "requestId": "synthetic-request-001",
  "deviceId": "configured-light",
  "action": "setPower",
  "arguments": { "on": false },
  "expiresAt": "2026-10-28T12:00:00Z"
}
```

`setBrightness` would carry an integer `percent` in 0–100 and require confirmed power/white-mode capability. `setPower` is explicit, never a network toggle. Names, fields, timeout limits and wire transport are proposals and require a final versioned contract before client implementation.

## Application operation

An application operation must return a typed outcome rather than Unit or English UI copy. Candidate outcomes are confirmed state, busy, unsupported capability, not configured/unavailable, expired, unauthorized, cancelled, and unconfirmed. Cancellation/timeout after a write is unconfirmed unless an independent read proves the requested result.

All consumers targeting a device must share serialization and bounded admission. A new server must not create a parallel device worker that races the UI's gateway. This coordination will be extracted when the first real authenticated command consumer is approved. Phase 1 deliberately does not add a permanent executor, command service, global registry or queue just for a proposed interface.

The request owner, maximum execution lifetime, cancellation linkage and process-recreation semantics must be explicit. No UI lifecycle owns a remote request. No always-on listener is enabled merely because a dashboard opens. Android foreground/background ownership is a concrete implementation decision for the real endpoint.

## Security gate before any exposure

The existing six-digit notification PIN over HTTP is not authorization for physical-device control. A future design must specify:

- Encrypted transport or a reviewed authenticated secure local channel; no plaintext physical control endpoint.
- Explicit pairing/user consent, revocation, secure credential storage and authorized client/device/action scope.
- Failed-auth throttling and bounded request parsing/admission before expensive work.
- Clock-skew/expiry limits and replay protection tied to authenticated client identity.
- Idempotency behavior for repeated IDs, including conflicting bodies, in-flight work, retention bounds and process death.
- No optimistic success, silent write retry or ambiguous toggle after uncertainty. A previously unconfirmed request stays unconfirmed until evidence resolves it.
- No tokens, notification bodies, device secrets, private addresses or client identity dumps in logs or status broadcasts.

A request ID alone is not replay protection. The notification gate's transient deduplication/acceptance limit is not a command authorization/idempotency implementation. Do not add a command field to notification v1 or interpret a notification body as an action.

## Required evidence for the future implementation

Use synthetic fixtures to test denied clients, revoked credentials, invalid capability/range, expired/replayed/conflicting requests, busy admission, cancellation before/after write and readback mismatch. Verify shared serialization and bounded ownership without operating a real lamp. Validate the chosen transport/platform lifecycle independently before enabling a listener. Record real-TV/Apple checks separately.
