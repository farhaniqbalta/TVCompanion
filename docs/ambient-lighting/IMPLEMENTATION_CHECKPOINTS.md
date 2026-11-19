# Ambilight implementation checkpoints

Target baseline `7ca23f8`. Local branch `feature/ambilight`.

| Gate | Status | Evidence and remaining acceptance |
| --- | --- | --- |
| C0 Preflight | PASS | Baseline matched origin/main. The [ADR](DECISIONS.md) and [owner diagnostic support matrix](CAPTURE_SUPPORT_MATRIX.md) record the design and stale report cell. |
| C1 Kotlin engine | PASS | Five pure JVM fixtures pass offline. Tests cover stride, area sampling and oversized per-edge allocations, corners/reversal/start offset, motion, smoothing, near-black tolerance and 24/30/60 FPS cadence. DDP fixtures cover 30/60/180/300/480/481/600 LEDs, reusable storage, RGB, offsets, lengths, PUSH and 4-bit sequence wrap. |
| C2 Capture integration | PARTIAL | Packaged manifest declares a private media-projection foreground service and required permissions. JVM fakes verify stale consent/epochs, revocation, 1,000 seeded sequences, keepalive, UDP failure, near-black detection and timestamp fallback. Android consent, OS revoke, lifecycle leaks, source compatibility, 20/30 FPS and playback overhead on the target TV remain NOT TESTED. |
| C3 TV UI | PARTIAL | Settings, persisted layout and profiles, explicit test/start/stop and dashboard focus tracking compile. APKs and app lint pass. On the 1280×720 Skyworth, verified the Ambilight card fits above the fold, D-pad navigation opens settings, empty-address validation focuses the input, and Back restores card focus. Leaving the Activity during active capture and narrow/large-text device layouts remain NOT TESTED. |
| C4 Stock WLED | NOT TESTED | Physical mapping, firmware version and realtime exit/power behavior require owner-approved hardware testing. See [WLED configuration](WLED_CONFIGURATION.md). |
| C5 Performance and release | PARTIAL | Builds, app lint, Android/JVM regressions and native AirPlay checks pass. A 30-minute soak, incremental CPU/PSS, capture-to-light delay, app update/sleep recovery and the measured C++ decision remain NOT TESTED. See [performance report](PERFORMANCE_REPORT.md). |

## Reproducible local verification

Used JDK 17, SDK 34, NDK 27.2.12479018 and CMake 3.22.1. From the repository root:

```sh
python3 scripts/prepare-airplay-native.py
python3 scripts/check-airplay-native.py
cd android
./gradlew --offline :core:ambilight-engine:test
./gradlew --offline --continue :core:ambilight-engine:test :core:domain:test testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
AMBIENT_GLSLC="$ANDROID_HOME/ndk/27.2.12479018/shader-tools/darwin-x86_64/glslc" \
  ./gradlew --offline :integrations:airplay-runtime:testDebugUnitTest --rerun
adb devices -l
```

159 JVM tests passed after rerunning the initially skipped AirPlay shader check with the NDK's host `glslc`. Twelve tests are in the Ambilight modules. Native host checks passed for audio Ambient, the shared playout clock, H.264 configuration and stereo ALAC. Lint reported zero errors and three warnings in existing AirPlay/notification code. `aapt dump xmltree` confirmed the packaged service type and permissions. Both the debug APK and unsigned release APK built.

The original verification run had no ADB device. The owner later approved a debug APK update on the Skyworth. A stock WLED controller was unavailable for capture and output testing. Power or firmware changes require owner approval. Progress and fixes are recorded in the [worklog](WORKLOG.md).
