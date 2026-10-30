# Notification HTTP v1

Development LAN contract adapted from TVNotify. Default TVCompanion port: **8766**, configurable in settings. One HTTP/1.1 request per connection; responses close the connection.

`POST /api/v1/notifications` requires `Authorization: Bearer <per-install PIN>`, JSON content type, and a content length from 1 to 4096 bytes. Header size is limited to 8192 bytes; transfer encoding and duplicate headers are rejected. The PIN and content are unencrypted; use synthetic non-sensitive messages on a trusted LAN.

```json
{
  "id": "demo-alert-001",
  "source": "alert",
  "title": "Delivery update",
  "body": "Your snacks are ready.",
  "ttlSeconds": 120,
  "durationSeconds": 8
}
```

Sources: `grab`, `shopeefood`, `gojek`, `alert`, `alert2`. Legacy aliases `shopee` and `test` map to `shopeefood` and `alert`. `message` is a fallback body alias; `sentAt` is a fallback for `timestamp`. Optional timestamps must be ISO-8601 with timezone. When absent, receipt time is used. TTL defaults to 120 seconds (1–300); display duration defaults to the configured setting (3–30 seconds). Future timestamps allow at most 30 seconds of skew.

`alert2` uses the same authenticated endpoint, admission limits, FIFO queue and expiry as other sources. It shows a large centered, dimmed TV overlay with a focused **Dismiss** action; Back also dismisses it. The overlay closes automatically after `durationSeconds` (3–30). Normal alerts keep their compact, non-interactive presentation. The TV's compact/full/privacy appearance setting still controls which title and body text is visible. A queued receipt means accepted for display, not that the overlay appeared over every foreground application.

IDs are optional generated strings, or nonblank strings up to 128 characters. Title/body/message fields are optional strings up to 512 characters; control characters are stripped. A blank title becomes `Delivery update`.

| Status | Meaning |
| --- | --- |
| 202 accepted | Queued; does not prove visibility over a protected player |
| 200 duplicate / filtered | Already seen or source muted |
| 400 | Invalid request or event field |
| 401 | Missing or wrong PIN |
| 404 | Unknown method/path |
| 410 | Expired or future timestamp |
| 413 / 415 / 431 | Invalid body length, content type, or header size |
| 429 | Rate limit or waiting queue full |
| 503 | Overlay permission unavailable, or `receiver_paused` while a request races shutdown |

Responses contain a JSON `status` string. `GET /health` is unauthenticated and returns `{"status":"ok","app":"TVCompanion"}` without PIN, content, or device state. No history or durable queue is provided.

Only one overlay is visible, with ten waiting events maximum and 256 remembered IDs maximum. Acceptance is capped at twelve events per eight seconds. Pause clears transient state. Start is manual after reboot. This receiver does not establish automatic iPhone notification capture.
