# Architecture

## Host and client roles

Google TV hosts TVCompanion orchestration, notification presentation and supported smart-device use cases. Apple devices are clients. Independently built applications are not peer hubs. The AirPlay receiver is packaged inside the single TVCompanion APK and runs in a private `:airplay` process. It owns AirPlay networking, decoding and rendering. Mac media goes directly to that process; the main process exchanges only receiver commands, status and lifecycle events over Binder/AIDL. No media frames or decoded audio cross that control connection.

A platform remote client can still use a separately validated stock TV remote service. Host/client roles do not require routing every Apple protocol through one generic server.

## Dependency direction

```text
app (composition + navigation)
  -> features:smart-home (rendering and input)
  -> features:notifications (settings UI + receiver composition)
  -> integrations:tuya-lan (network protocol + encrypted device configuration)
  -> core:designsystem (TV presentation primitives)

features:smart-home -> core:domain, core:designsystem
features:notifications -> core:domain, core:designsystem
integrations:tuya-lan -> core:domain
core:domain -> Kotlin/JDK only
```

The app chooses the concrete `LightGateway` and injects it into `LightController`. The light screen observes normalized `LightState` and sends `LightCommand`; it never receives Tuya DPS, JSON, keys, or sockets. Replacing the light UI requires no protocol changes.

`core:domain` contains light commands, capabilities represented by validated light state, the gateway port, notification events/queue, and UI-independent light/notification controllers. The controller lives under `application/`, distinct from domain models. The light controller uses one worker, accepts one operation at a time, and releases observers and work on close. Its owned gateway closes active socket I/O, including negotiation; fragmented reads share an absolute session deadline. Dispatch is injected; domain/application code does not import Android.

The notification feature is a vertical module with separate presentation and infrastructure packages. Its UI-independent application controller and preference/runtime ports live in `core:domain`, with Android adapters supplied by the feature. Its JSON gate and HTTP listener are infrastructure; pure event/queue behavior is in the core. A pure `ReceiverSnapshot` represents lifecycle phase/issue and non-content receipt metadata. In the feature, `ReceiverSession` owns the bounded listener/queue/presentation session; `ReceiverService` owns Android foreground lifecycle and `AndroidNotificationOverlay` owns rendering. The process-local snapshot store is immutable to readers and broadcasts drive screen updates without status timers. Display text is mapped in presentation. Android service lifecycle and overlay rendering remain outside the domain. The feature owns receiver permissions and service lifecycle; the dashboard does not need to know its transport implementation.

Start with these few real boundaries. Split further only when dependencies, ownership, testing, or build time justify it. Gradle modules are not separate processes or automatically loaded runtime plugins.

## Lifecycle and resource rules

- No lamp polling, wake locks, automatic reconnect loop, or startup service.
- Device access and encryption run on a single background worker while the light screen is active. Close connections after operations. Do not retry writes automatically.
- Backgrounding the dashboard closes its controller and removes its view tree. Backgrounding settings releases its binding and pending UI callbacks too.
- Only the explicitly started notification receiver stays active. One blocking listener thread sleeps awaiting a connection; one bounded input is processed at a time. Pause closes both listener and current connection.
- Headers are capped at 8 KiB, bodies at 4 KiB, waiting notifications at ten, visible overlays at one, and remembered IDs at 256. Event acceptance is limited to twelve per eight seconds. Parsing uses one fixed header buffer.
- Overlay timers exist only while showing an event; reduced motion is supported. No infinite animation or periodic status timer.
- Drop network response failures without logging bodies or secrets. Surface command uncertainty and stale state explicitly.
- Do not add a separate Android service for Apple remote commands unless a verified protocol requires one. Investigate the TV's existing paired remote service first.

These are implementation rules, not measured CPU/RAM claims. No performance benchmark is required for this foundation.

## Apple boundary

The Apple app belongs in `ios/`, with presentation, application/domain, and protocol adapters kept separate. iPhone/iPad are intended targets; Mac availability and interaction need an explicit platform decision. Pairing, Keychain storage, connection lifecycle, and commands belong behind a remote-control port. No Android code or shared runtime is required. Do not claim compatibility before actual protocol work and device checks.

## Migration boundary

Original apps remain independent. Reuse source and tests, never private credentials, local files, signing keys, package identity, or deployment assumptions. Do not modify CleanYourTV. A launcher card may be added only as a separate, optional static launch integration; no embedded live controls are assumed.

## Future integration contracts

The [client-command draft](../contracts/client-commands-draft.md) is distinct from notification v1 and requires secure, scoped authorization before exposing physical commands. The [external receiver control draft](../contracts/external-receiver-control-draft.md) keeps future Binder/AIDL types outside the pure domain and all media processing in the same APK's dedicated receiver process. The client command endpoint remains design-only; the private AirPlay control port is implemented in the Phase 2 video preview. See the [Phase 1 implementation record](phase-1/IMPLEMENTATION_CHANGELOG.md).

## AirPlay video preview process boundary

The foreground video preview now implements this process boundary. See the [single-APK integration record](phase-2/SINGLE_APK_INTEGRATION.md) for actual modules, lifecycle policy and evidence; the [Phase 2 plan](phase-2/PLAN.md) retains broader feasibility/release gates.

```text
TVCompanion.apk
  main process: existing features + AirPlay UI/controller + control adapter
       | Binder/AIDL: commands, status, lifecycle only
  :airplay: private service + playback Activity/surface + JNI + UxPlay + decode/render
       ^ direct AirPlay network connection from the Mac
```

The focused modules are `features:airplay`, `ipc:airplay-contract`, `integrations:airplay-runtime` and `integrations:uxplay-native`. The implementation follows the [receiver boundary review](phase-2/RECEIVER_BOUNDARY_REVIEW.md), separating the reusable Android service/playback host from the build-time-selected engine; the pure control contract contains no UxPlay types. The app composes them into one artifact. The pure application port and versioned control AIDL are now implemented. Existing notification and smart-home modules must not depend on the native integration. A focused pure application port is added only when a real receiver consumer needs it; Android Binder types remain outside `core:domain`.

The receiver service owns native initialization, sessions and deterministic cleanup. The playback Activity lives in `:airplay` too, so its surface stays local to the renderer. The current candidate implements opt-in service-owned availability independently of Surface lifetime, with connected-device/active-media foreground-service types and visible Open/Stop controls. The 0.5.2 policy separates discovery from existing-session retention: idle listening defaults off, connected-session retention defaults on, hidden audio defaults off, and automatic video presentation defaults on. Leaving without a session or idle-listening opt-in releases the engine. A retained session can withdraw discovery while keeping its networking alive. Turning retention off ends a departing session; if listening remains enabled, idle discovery restarts only after cleanup. See [audio/background policy](phase-2/AUDIO_AND_BACKGROUND.md). Do not load native libraries or initialize main-process features through receiver-process startup. Notification process-local state is not a cross-process AirPlay status store.

Use `android:process=":airplay"` and private components (`exported=false`); do not conflate this with Android's permission-restricted `isolatedProcess` setting. Separate processes share device resource limits and do not constitute a licensing conclusion. No permanent native process, boot startup, dashboard polling, plugin registry or event bus is introduced. An optional standalone feasibility harness is development-only, never a second production installation.

## Reusable feasibility shell

The [optional lab](../experiments/airplay-lab/README.md), enabled with `-PairplayLab=true`, now uses the same runtime and selected engine. It retains a separate development identity and is not a dependency of the main app. Production installation requires only TVCompanion. See [accepted integration and next steps](phase-2/SINGLE_APK_INTEGRATION.md).

The audio/background candidate starts networking independently of a render Surface, keeps AAudio/codec work local to `:airplay`, and attaches/detaches playback without transferring media over IPC. Audio failure is a nonfatal typed status where video can continue. Availability remains default-off and is not restarted after process recreation or boot.

Background video presentation is now requested once per video connection where Android permits it, using the existing user-granted launch exception on the validated TV; audio-only sessions remain background. The foreground-service notification is not assumed to be accessible on all TV firmware. App Open/Stop controls remain available. Presentation policy neither retries indefinitely nor reopens a session simply because the user pressed Back.

The Android-local engine port independently controls discovery advertising and audio permission; neither operation changes domain contracts to vendor/JNI types. Control protocol v3 carries independent policy flags. Native video startup waits for bounded H.264 parameter sets and retains at most 2 MiB / 60 compressed frames / one second while no Surface is available. Those frames prime decoder references on attachment; stale decoded output is discarded. Long hidden sessions do not accumulate media. See the 0.5.2 section of the audio/background record for limitations and device validation status.
