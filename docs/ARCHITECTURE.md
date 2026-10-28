# Architecture

## Host and client roles

Google TV hosts TVCompanion orchestration, notification presentation and supported smart-device use cases. Apple devices are clients. Independently built applications are not peer hubs. A future AirPlay receiver remains a separate, independently usable APK/process and owns its media pipeline. Mac media goes directly to it; TVCompanion only controls availability/status through a future adapter. No frames/audio traverse TVCompanion or Binder.

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

The [client-command draft](../contracts/client-commands-draft.md) is distinct from notification v1 and requires secure, scoped authorization before exposing physical commands. The [external receiver control draft](../contracts/external-receiver-control-draft.md) keeps future Binder/AIDL types in an Android adapter and all media in the independently usable receiver APK. These are design boundaries, not implemented endpoints/ports. See the [Phase 1 implementation record](phase-1/IMPLEMENTATION_CHANGELOG.md).
