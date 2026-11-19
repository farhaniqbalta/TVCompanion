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
