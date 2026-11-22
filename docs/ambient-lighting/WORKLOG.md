# Ambilight worklog

## 2026-11-19

- Created the dedicated `feature/ambilight` branch and sibling worktree from `7ca23f8`, which matched the remote `main` at preflight. The original checkout remains on `main`.
- Read the final worker handoff and owner capture report. Recorded architecture decisions and the diagnostic-based compatibility matrix. Flagged the stale projection cell in the diagnostic hardware table.
- Located JDK 17 and Android SDK 34. `adb devices -l` returned no connected device. No app or firmware has been installed.
- C1 red test confirmed missing engine symbols. First implementation run passed stride/mapping, smoothing and cadence. The DDP fixture caught a missing PUSH bit caused by a packet-buffer property shadowing the RGB frame size.
- C1 `./gradlew --offline :core:ambilight-engine:test` passed all four fixtures after the PUSH fix. Verified DDP header values and flags against WLED sender and receiver sources. Firmware acceptance remains open.
- C2/C3 integrated Android capture, bounded WLED control, native settings and a two-row dashboard. The app compiled with `:app:compileDebugKotlin`; the first controller suite exercised 1,000 seeded action sequences.
- Independent Superpowers review found a stale-start foreground-service crash path, epoch reuse after process recreation, an overly broad address check, delayed keepalive and an overstrict black detector. Updated service promotion, consent identity, local IPv4 validation, keepalive deadlines and near-black detection.
- The isolated worktree's first APK build exposed the existing native AirPlay preparation prerequisite. Copied the cached archives and ARMv7 crypto artifact from the original checkout, then ran the repository preparation script. It verified all source checksums and applied the existing patches.
- Final local verification passed. Debug and unsigned release APKs built. App lint reported zero errors and three existing warnings. Packaged manifest lists the private media-projection service and permissions.
- All 159 JVM tests passed, including 12 Ambilight tests. The initial run skipped one AirPlay shader test. Reran AirPlay unit tests with the NDK's `glslc` and confirmed zero skips. The native host regression script also passed.
- C0 and C1 passed. C2, C3 and C5 are partial pending TV checks. C4 remains not tested pending physical ESP32/WLED hardware. The second ADB check still found no connected device. See [checkpoint evidence and exact commands](IMPLEMENTATION_CHECKPOINTS.md).

## Device checkpoint follow-up

- Owner approved proceeding with TV validation. Rechecked `adb devices -l` and `adb mdns services`. Both returned empty lists. The debug APK is available. Installation and TV checks are pending a reachable ADB endpoint.

- Skyworth reappeared in ADB. Installed the debug APK in place with `adb install -r`. Verified native D-pad entry into Ambilight, Off state, and Back restoring dashboard focus. Owner confirmed stock WLED is not available yet.
- Reviewed the actual 1280×720 UI. The original second dashboard row was clipped on first load and the settings screen put long help text before the controller field. Compacted the dashboard cards, aligned the Ambilight status with its action, put screen sync and WLED setup side by side, moved compatibility into a dialog, and added inline validation and field focus. Narrow or large-text screens use one column.
- Rebuilt debug and unsigned release APKs and reran Ambilight JVM tests and app lint. Installed the revised debug APK on the Skyworth. WLED output and active capture remain open.

## 2026-11-21 — WLED hardware follow-up

- WLED's browser UI worked. The TV reached WLED over TCP, but the Android app's merged localhost-only cleartext policy blocked its framework HTTP client. WLED 16.0.1 also reported 30 LEDs, below the app's initial 60-LED layout.
- Switched WLED control to a bounded direct HTTP connection for the validated private/link-local address. Kept the app's existing network security policy and removed the ineffective manifest cleartext opt-in. The TV now reports the two counts when layout exceeds the controller count.
- Installed the debug APK and reproduced the explicit 60-versus-30 message. Applied a provisional 10/5/10/5 top/right/bottom/left layout. WLED reported idle → DDP realtime from the TV → idle, and the TV displayed “Test sent.” Owner confirmation of visible colors and mounting order remains open.
- Ambilight engine and feature JVM tests, app lint and debug APK build passed.

- The owner confirmed live screen sync works on the current 30-LED setup. Screen-sync stop modes and performance remain open.
- The owner recounted the physical strip and confirmed 60 LEDs. Earlier sync evidence still covers the 30-LED software configuration. Full-strip mapping and operation remain open.

## 2026-11-22 — Full strip, UI and colour follow-up

- Split Ambilight into an everyday screen (status, start/stop, described styles, one brightness stepper, stop choice) and Strip setup (address, Test LEDs, edge-count steppers, layout diagram). Settings save as they change. Styles no longer cap brightness.
- Installed setup is 129 LEDs on WLED 16.0.1 with a 40/23/41/25 layout. The owner confirmed live screen sync.
- A dark red scene looked pink on the strip. Screen capture analysis showed raw values sent without gamma, with WLED's realtime gamma disabled. The owner enabled it in WLED.
- Top and bottom LEDs now sample inside letterbox bars, and style and brightness changes apply during sync. Ambilight JVM tests and app lint pass.
