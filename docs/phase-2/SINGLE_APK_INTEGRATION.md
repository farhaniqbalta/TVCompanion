# Phase 2 — single-APK AirPlay video integration

Date: 2026-10-29. Branch: `plan/phase-2-airplay`. Version: **0.4.0-airplay-preview**, versionCode 5, unchanged application ID `id.poncol.tvcompanion`. The user approved proceeding beyond the standalone harness, then confirmed the integrated app ran perfectly and requested no further testing at this checkpoint.

## Implemented boundaries

```text
app: build-time composition + dashboard/navigation
  -> features:airplay: main-process presentation + Android control adapter
  -> integrations:airplay-runtime: private :airplay service/playback host
  -> integrations:uxplay-native: selected engine, JNI and native build

features:airplay -> core:domain, core:designsystem, ipc:airplay-contract
integrations:airplay-runtime -> core:domain, ipc:airplay-contract
integrations:uxplay-native -> integrations:airplay-runtime API, core:domain
ipc:airplay-contract -> core:domain
core:domain -> Kotlin/JDK only
```

`TVCompanionApplication` implements the runtime’s engine-provider interface. Its construction loads no native code and starts no feature workers. Engine/capability selection occurs through composition; the runtime has no dependency on UxPlay. The engine exposes normalized signals and an Android-local Surface attachment API; these are not the pure application contract.

`AirPlayReceiverControl` and immutable availability/capability/lifecycle/connection/error models live in the pure domain. Receiver dimensions are unknown until supplied by composition; the validated implementation reports its video-only preferred mode. AIDL uses bounded primitive control/status values, with version validation and mapping outside domain. No frame, audio buffer, Surface, native pointer or vendor configuration crosses IPC.

The main-process settings adapter owns a scoped service binding, observers, a maximum of four pending commands, five-second reply deadlines, stale-connection/revision rejection and unsubscription. Closing a UI client releases its own connection; it is not an implicit receiver-wide stop command. Service Binder callbacks serialize lifecycle commands onto the receiver process’s main handler; native startup/shutdown use the engine’s worker.

The service owns a pure lifecycle session with explicit accepted/starting/ready/stopping/failed states. Readiness follows native discovery startup. Stop invalidates old callbacks, waits for actual engine release and prevents overlap with a new engine. The neutral runtime hosts its playback Activity and Surface in `:airplay`, so direct Mac networking, JNI, decode and rendering all stay local to that process. Separate process assignment is not `isolatedProcess=true` or a license exemption.

## Current lifetime policy

This checkpoint is intentionally **foreground-only video**. A user opens the receiver playback screen; its local Surface and engine session become active. Back, backgrounding or Surface loss requests shutdown. The service is bound to visible consumers, and the playback Activity also starts it while foreground-visible solely to retain ownership through asynchronous cleanup. Once the surface is gone and cleanup completes, it calls `stopSelf()`; there is no persistent listener, foreground notification, boot receiver or restart loop.

The temporary started/bound lifetime avoids destroying/recreating the host while native shutdown is still pending. It is not a background availability option. A cached Android process may remain after cleanup; absence of a PID is not required for correct resource release. [Android bound-service lifecycle](https://developer.android.com/develop/background-work/services/bound-services), [foreground/background execution rules](https://developer.android.com/about/versions/oreo/background).

Existing notification and Tuya source/behavior remain unchanged. AirPlay is not a dependency of those modules. Its new home card uses the existing shared TV design primitives, with explicit focus restoration. The standalone lab is now a thin optional shell sharing the same runtime and engine; it is not required on the TV.

## Native build and provenance

The validated UxPlay/libplist/OpenSSL sources and native adapter moved from the experiment into `integrations:uxplay-native`. `scripts/prepare-airplay-native.py` owns pinned fetching, source verification, patches, static libcrypto preparation and generated license/source notices. The old lab preparation entry delegates to it. No downloaded source, native binaries, credentials or private test data are tracked. The two patches reproduce exactly from pristine pinned UxPlay source.

CMake uses Android MediaCodec and the validated 32-bit ARM ABI. Audio packets may negotiate under the reference upstream discovery profile but are discarded; no audio playback is claimed. The same AirPlay/RAOP advertisement profile that passed the Mac harness gate is retained. GStreamer is not bundled in this implementation. Root/per-module licenses have not been selected or replaced; the [licensing review](../LICENSE_COMPLIANCE.md) remains a distribution gate.

Android CI now installs the pinned native toolchain, caches native preparation, builds the one-APK preview and runs lifecycle/IPC regression suites. It continues to upload reports only, not APKs or native binaries. A local pass is not a claim of a completed remote CI run.

## Recorded evidence

- Main APK and shared-runtime optional lab built locally.
- **73 tests passed**: existing 59 plus 11 receiver lifecycle and 3 wire-validation cases. Cases cover absent display/no allocation, accepted versus ready, stop during startup, true asynchronous cleanup, stale session events, synchronous failure/reentrancy, typed failure/retry, idempotent close, connection versus video, capabilities and malformed wire values.
- Main app lint: zero errors, one warning for deliberately absent ChromeOS/x86 ABI support. This target-specific preview is not a Play Store release.
- Main APK contains the selected native receiver library and generated upstream notices. Core remains free of Android/JNI/Binder/native imports.
- Installed as an update with the same signing identity; all pre-existing preference files were verified byte-for-byte preserved. The notification receiver was not running before this update. No lamp writes or credential migration were performed.
- The standalone lab playback was left before the main-app update. The main dashboard was launched. The user reported that the integrated app ran perfectly and explicitly declined further testing.
- No extra TV tests, benchmarks, intentional process crashes, notification injection or lamp operations were performed after that instruction. The earlier harness’s true Mac extension acceptance remains separately recorded in [the experiment evidence](TV_RECEIVER_EXPERIMENT.md).
- ADB closure remains pending; no debugging-off/revocation/reboot/closed-port check is claimed. No device identifiers, private endpoints, preference contents or captures are published.

This is user acceptance of the current checkpoint, not a claim that every lifecycle path, device, audio mode, long session or public-distribution requirement has been validated on hardware.

## Next requested work

1. **Audio in the existing engine boundary.** Add screen-mirroring audio decode/output, Android audio focus, mute/volume policy, bounded buffering, A/V synchronization and teardown. Keep compressed/decoded audio local to `:airplay`; extend neutral capabilities only for modes actually supported. Treat audio-only ALAC and other formats as explicit scope decisions, not automatically proven by screen video.
2. **Opt-in idle/background availability.** Let the service own desired receiver lifetime independently of Activity/Surface lifetime. Add an explicit Keep AirPlay available setting and reliable stop controls; default off, with no automatic boot start. Select a valid foreground-service policy for discoverable idle separately from active media playback; do not assume mediaPlayback authorizes indefinite idle discovery. Split engine network startup from local surface attach/detach, since the current foreground API requires a Surface to start. The playback Activity owns a render surface, not the receiver’s existence. Define incoming-session UI behavior within Android TV’s background launch restrictions, reattachment, failures and cleanup.
3. Keep audio and background availability as separate reviewable changes. Preserve current notifications/smart-home behavior, implementation interchangeability and the licensing gate. No additional benchmark suite is requested.
