# Quick Controls v1 — iPhone ↔ TV control contract

Status: v1, implemented by the iOS Controls tab and the Android `ControlService`; device-checked 2026-11-22. Replaces the design-only `client-commands-draft.md`. Design: `docs/superpowers/specs/2026-11-22-quick-controls-design.md`.

## Transport

- **Connection:** TCP port **7380** on the TV, using the TV's existing profile host. TLS 1.2+ with mutual TLS.
- **Server key:** RSA-2048. The client pins `SHA-256(server RSA public key, PKCS#1 DER)`, the same pin form as the Google TV remote.
- **Client identity:** the iOS app's existing remote client certificate (`RemoteIdentityStore`).
- **Framing:** each message is a varint length prefix followed by UTF-8 JSON, the same framing as `FrameDecoder`. A request is at most 4096 bytes; a response at most 16384.
- **Connection lifetime:** one request and one response, then close. 10 s deadline overall. The TV accepts at most 2 connections at a time.
- **Notification PIN:** never used for controls.

## Pairing

1. The TV owner opens "Pair iPhone" on the TV. The TV shows a 6-digit code for 2 minutes.
2. The iPhone connects without a pin. This is allowed only for an explicit pairing attempt.
3. The iPhone sends `{"v":1,"cmd":"pair","proof":"<64 lowercase hex>"}`, where `proof = SHA-256( utf8(code) ‖ SHA-256(clientRSA) ‖ SHA-256(serverRSA) )`.
4. If the proof matches, the TV stores `SHA-256(clientRSA)` (at most 4 clients) and replies `ok`. The iPhone stores the server pin.
5. If it doesn't, the TV replies `denied`. Three wrong proofs close the pairing window.
6. Threat model: binding the key hashes stops a captured proof being replayed to a different server. An active LAN attacker during the pairing window is out of scope, the same as Google TV pairing.

Every other command requires a paired client certificate. Otherwise the TV replies `denied`.

## Requests

```json
{"v":1,"cmd":"status"}
{"v":1,"cmd":"pair","proof":"…"}
{"v":1,"cmd":"ambient.set","open":true,"look":"aurora"}
{"v":1,"cmd":"ambient.set","open":false}
{"v":1,"cmd":"ambilight.set","on":true}
{"v":1,"cmd":"ambilight.look","style":"soft"}
{"v":1,"cmd":"ambilight.look","contrast":"dramatic"}
{"v":1,"cmd":"light.set","on":false}
{"v":1,"cmd":"light.preset","name":"blue"}
{"v":1,"cmd":"notifications.set","on":true}
{"v":1,"cmd":"airplay.set","available":false,"confirm":false}
```

- **Explicit states only:** every command sets a state; there are no toggles.
- **Looks:** `look` must be one of the status `looks`.
- **Light presets:** `name` must be one of `presets`, which includes `white`.
- **Ambilight look:** sends `style`, `contrast`, or both. Each must be one of the status `styles` or `contrasts`. The setting is saved and applies to a running sync at once. It also works while Ambilight is off, and replies `ok`.
- **AirPlay confirm:** `confirm` is only needed when the TV previously answered `would_end_playback`.

## Response (every request)

```json
{"v":1,"outcome":"ok","reason":null,"status":{
  "ambient":{"state":"available","look":"aurora","looks":["aurora","strings","dusk","ripples"]},
  "ambilight":{"state":"off","style":"cinema","styles":["cinema","responsive","soft"],"contrast":"normal","contrasts":["normal","dramatic"]},
  "light":{"state":"on","preset":"blue","presets":["red","orange","yellow","green","blue","purple","white"]},
  "notifications":{"state":"listening"},
  "airplay":{"state":"playing","available":true}
}}
```

- **`outcome`** is one of: `ok`, `pending`, `busy`, `unavailable`, `unconfirmed`, `needs_setup`, `needs_approval`, `invalid`, `denied`.
  - `pending`: accepted, and confirmation comes later. The client re-reads `status` once after about 2 s.
  - `unconfirmed`: the write may have applied. The client never retries it by itself.
- **`reason`:** optional short machine word, such as `would_end_playback` or `playing`. It is never shown to users verbatim.
- **`status`:** the state of all five features after the command, as the TV observes it. It is absent only for `denied` and `pair`.
- **State values:**

| Feature | `state` values |
|---|---|
| Ambient | `unavailable` (no AirPlay music) · `available` · `showing` |
| Ambilight | `off` · `needs_approval` · `starting` · `running` · `stopping` · `not_set_up` · `error` |
| Light | `on` · `off` · `unavailable` · `not_set_up` · `busy` |
| Notifications | `listening` · `starting` · `paused` · `failed` |
| AirPlay | `stopped` · `starting` · `ready` · `playing` · `failed`, plus `available` (background listening preference) |

- **Ambilight look fields** are additive. TVs from before `ambilight.look` send only `state`, and clients hide look options when `styles`/`contrasts` are absent.
- **Unknown values:** clients must treat an unknown `state` or `outcome` as `unknown` and disable that control, not fail the whole response.
