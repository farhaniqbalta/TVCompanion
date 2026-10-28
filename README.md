# TVCompanion

<img src="brand/banner.svg" width="320" alt="TVCompanion logo" />

A local-first Google TV hub with Apple-device clients. Control your room, receive quiet updates, and grow capabilities without coupling the UI to device protocols.

## Platforms

| Directory | Target | Status |
| --- | --- | --- |
| [`android/`](android/) | **Android TV / Google TV**, with TV screens and D-pad input; not an Android phone app | Initial native application with Bardi light controls and notification receiver |
| [`ios/`](ios/) | **iPhone and iPad** companion, with **Mac support planned for evaluation** | Architecture placeholder; remote pairing and control are not implemented yet |
| [`contracts/`](contracts/) | Integration documentation and synthetic examples | Notification HTTP v1 contract |
| [`docs/`](docs/) | Architecture, design, development and decisions | Foundation documentation |

Google TV is the primary orchestration host; iPhone, iPad and future Mac software are clients, not peer hubs. A planned independent AirPlay receiver will own media discovery, decoding and rendering; Mac media will go directly to that APK, never through TVCompanion or its control IPC. AirPlay and true extended desktop are not implemented or verified.

`ios/` is the Apple application workspace name, not a claim of current macOS support. Android and Apple applications have independent builds, dependencies, signing, and releases. CleanYourTV remains an external launcher; TVCompanion does not depend on or modify it.

## Android application

- A TV dashboard with two large feature cards, shared typography, focus states and native vector icons.
- A compact light-control page and categorized notification settings with local previews, explicit appearance choices and masked pairing details. See the [UI/UX research](docs/UI_UX_RESEARCH.md).
- Direct Tuya 3.5 LAN light status, power, and white brightness, adapted from the existing Bardi proof of concept. Writes require fresh state and readback; failures remain unconfirmed until refreshed.
- Notification settings, source filtering, previews, transient non-interactive overlays, expiry, deduplication, and bounded queues, adapted from TVNotify.
- No background lamp polling, dashboard animation loop, notification history, cloud bridge, analytics, automatic receiver restart, or boot service.
- Protocol behavior, UI-independent state handling, and transport regression tests.

The new application identity is `id.poncol.tvcompanion`. It does not replace existing apps or inherit their private settings. Notification reception is opt-in and defaults to port **8766**, so it can coexist with TVNotify's 8765 during development. Use one receiver for normal operation to avoid duplicate overlays and resident processes.

The receiver currently uses authenticated **unencrypted LAN HTTP** inherited from the prototype. Use non-sensitive demo messages on a trusted LAN. Encrypted transport and stronger pairing remain release work. Automatic capture of other iPhone apps' notifications is not implemented or established by this receiver.

## Get started

See [Android setup](docs/ANDROID_SETUP.md) for build requirements, optional development provisioning, and notification setup. Actual checks and remaining gaps are recorded in [verification](docs/VERIFICATION.md). No device credentials or signing secrets are required to build or run unit tests.

```sh
cd android
./gradlew :app:assembleDebug :core:domain:test :integrations:tuya-lan:testDebugUnitTest :features:notifications:testDebugUnitTest :app:lintDebug
```

Requires JDK 17 and Android SDK 34. The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. This is a development foundation, not a production release. The app is installed and basic functionality is user-confirmed; broader lifecycle and overlay coexistence still require real-device validation; benchmark work is intentionally out of scope.

## Architecture and contribution

Read [architecture](docs/ARCHITECTURE.md), [design](docs/DESIGN.md), [decisions](docs/DECISIONS.md), and [contribution guidelines](CONTRIBUTING.md). Keep UI replaceable, normalize vendor state behind adapters, and introduce abstractions only at real boundaries.

No repository license has been selected yet. See [source provenance](docs/PROVENANCE.md) for reused components. Do not assume an open-source license from repository visibility alone.
