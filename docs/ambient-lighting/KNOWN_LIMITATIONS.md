# Known limitations

- Capture coverage depends on the TV, player and session. See the [diagnostic support matrix](CAPTURE_SUPPORT_MATRIX.md). The near-black hint is based on consecutive edge samples and may appear in dark scenes or letterboxed content.
- Android controls projection consent and can stop it. The app requests the entire display on Android 14 or later. TV firmware can override consent options and can hide foreground notifications. Stop remains available from Ambilight settings.
- On Android 14, a non-16:9 captured region uses the fixed 160×90 surface with a centered sampling crop based on the platform resize callback. This fallback and any OEM-specific scaling require TV testing. Older Android versions lack the content resize callback.
- WLED control uses cleartext HTTP to a manually configured private or link-local IPv4 address. Android's cleartext opt-in is app-wide because the destination is entered at runtime. WLED requests disable proxies and redirects. Timeouts bound connection and read inactivity, and response bodies have a size and elapsed-read limit. A peer that continuously trickles response headers can extend a request.
- UDP loss can drop lighting updates. An HTTP probe does not verify delivery or physical LED colors. If the app or network stops before it can send the exit request, WLED's finite realtime timeout is the fallback.
- Settings are saved before starting or testing and are read as a session snapshot. Stop screen sync to edit them.
