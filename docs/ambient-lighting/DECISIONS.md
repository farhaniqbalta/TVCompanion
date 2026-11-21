# Ambilight decisions

Baseline is `7ca23f8`, matching origin/main at preflight. The existing dashboard has Light, Notifications and AirPlay. Boot and application defaults already manage AirPlay and notifications. Ambilight is a manually started screen-sync service in the main process, independent of the AirPlay audio Ambient visualizer and the Tuya light gateway.

The two modules are the pure Kotlin/JVM `:core:ambilight-engine` and Android `:features:ambilight`. The former owns layout, sampling, smoothing, cadence and DDP. The latter owns consent, session lifecycle, storage, WLED control and native TV views.

Use 160×90 RGBA latest-image capture with an up-to-30 FPS deadline and a reusable RGB/DDP path. A capture looper owns frame resources and a control executor handles bounded WLED operations. The stock WLED controller receives unicast DDP on UDP 4048. Stop requests realtime exit, with an optional power-off policy.

The diagnostic supports this design conditionally. The installed product must still demonstrate moving color output and measure overhead on the target TV.

Settings changes require Stop so the session can use one validated layout and address snapshot. Profile caps limit the user-set maximum. Private/link-local IPv4 is supported for direct WLED control. The merged app network policy permits framework cleartext HTTP only to localhost. WLED uses a dedicated direct HTTP socket restricted to its private or link-local address and avoids redirects and proxies.

On resize, the capture surface stays 160×90 and the sampler follows the centered content region. Non-16:9 and OEM behavior require device verification.
