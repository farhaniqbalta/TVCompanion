# Project history

Detailed per-phase notes moved here from the README on 2026-11-22, unchanged. Current behaviour is summarised in the [README](../README.md); device results are in [verification](VERIFICATION.md).

## Android application

- A TV dashboard with two large feature cards, shared typography, focus states and native vector icons.
- A compact light-control page and categorized notification settings with local previews, explicit appearance choices and masked pairing details. See the [UI/UX research](UI_UX_RESEARCH.md).
- Direct Tuya 3.5 LAN light status, power, white brightness and a guarded HSV color-control preview, adapted from the existing Bardi proof of concept. Writes require fresh state and readback; failures remain unconfirmed until refreshed.
- Notification settings, source filtering, previews, transient non-interactive overlays, expiry, deduplication, and bounded queues, adapted from TVNotify.
- `alert2` uses the existing notification endpoint and a large, dismissible TV overlay. Existing appearance/privacy settings and queue limits still apply.
- An AirPlay preview card opens the same APK’s receiver: native networking and video rendering stay in `:airplay`, with a replaceable engine and control-only AIDL. Discovery, session retention, hidden audio and automatic video presentation have separate controls.
- No background lamp polling, dashboard animation loop, notification history, cloud bridge, analytics, or automatic receiver retry loop. Notification and AirPlay boot starts are independent opt-in choices.
- Protocol behavior, UI-independent state handling, and transport regression tests.

The new application identity is `id.poncol.tvcompanion`. It does not replace existing apps or inherit their private settings. Notification reception starts by default once its permissions are granted (see *Receiver defaults*) and uses port **8766**, so it can coexist with TVNotify's 8765 during development. Use one receiver for normal operation to avoid duplicate overlays and resident processes.

The receiver currently uses authenticated **unencrypted LAN HTTP** inherited from the prototype. Use non-sensitive demo messages on a trusted LAN. Encrypted transport and stronger pairing remain release work. Automatic capture of other iPhone apps' notifications is not implemented or established by this receiver.

The [iPhone/iPad app](../ios/README.md) pairs with Google TV Remote v2 for D-pad, media keys, voice and focused TV text entry, and sends `alert`/`alert2` messages through the TVCompanion notification receiver. Its pure protocol kit, Keychain-backed credentials, network adapters and SwiftUI views have separate boundaries. Local builds pass, and the user reports Phase 3 device acceptance on the tested setup.

## Phase 3 — iPhone and iPad companion

Phase 3 is merged into `main` and accepted for the tested home setup by user report. It includes the native iPhone/iPad remote, notification sender, TV-side urgent `alert2` overlay, and the subsequent iOS remote redesign. See the [Phase 3 acceptance record](phase-3/README.md). The combined Android preview uses versionCode 21. On the TV, **Notifications → Start after TV reboot** restores the notification listener; **AirPlay → Start after TV reboot** restores Background listening, Keep session connected, and Background audio. Both choices default off and require notification permission; the notification listener also requires overlay permission. Boot starts the AirPlay receiver, not a past sender session.

**Receiver defaults.** Opening TVCompanion starts the notification listener (when notifications and overlay permission are granted) and AirPlay Background listening (when notifications are granted), once per app process; Background audio is on by default. Nothing is saved: a receiver or option the user turns off stays off until the main app process restarts (a boot start counts as that process's start). After an app update or reinstall (`MY_PACKAGE_REPLACED`) both receivers restart with the same permission checks. Android does not let a freshly installed app start itself, so a first install starts its receivers on the first launch after notification (and, for notifications, overlay) permission is granted; on Android 13+ that permission is not granted at install. Boot behaviour is unchanged and still follows the two *Start after TV reboot* choices.

## Quick Controls — iPhone and iPad

The iOS **Controls** tab switches TV features without opening their TV screens: Ambient (visualizer and look, while AirPlay music plays), Ambilight start/stop, the Bardi light (power and colour presets), the notification receiver and AirPlay background listening. Tiles show only the state the TV confirms; there is no background polling.

- **Pairing:** on the TV choose **Pair iPhone for Controls** on Home and enter the 6-digit code on the iPhone. Up to four iPhones or iPads can be paired; the same screen forgets them.
- **Transport:** mutual TLS on port 7380 with an AndroidKeyStore server key and pinned keys on both sides ([contract](../contracts/controls-v1.md)). The notification PIN is never used for controls.
- **TV side:** a main-process foreground service that runs only while a device is paired or a code is showing. Each command goes through the feature's own headless entry point, so a failed or disabled feature never blocks the others.
- **Ambilight without the TV prompt:** optional, see [Android setup](ANDROID_SETUP.md#iphone-quick-controls).

Design: [Quick Controls design](superpowers/specs/2026-11-22-quick-controls-design.md). Device results: [verification](VERIFICATION.md).

## Phase 1 refactor

The `review/phase-1-architecture` branch contains a targeted refactor: owned Tuya cancellation and fragmented-read deadlines, typed notification lifecycle state, and testable listener/overlay session coordination. The accepted Phase 1 candidate was `0.3.0-phase1-preview`, with 59 passing tests and zero lint findings, with installation and lamp/notification functionality now user-confirmed. See [implementation details](phase-1/IMPLEMENTATION_CHANGELOG.md). The command endpoint remains design-only. The Phase 2 preview now implements the private receiver IPC boundary; no external command server is added.

## Phase 2 integration

`0.4.0-airplay-preview` bundles the receiver in the single TVCompanion APK. The main-process AirPlay screen controls a private `:airplay` service through AIDL; JNI, native networking, MediaCodec and the playback surface stay in that process. See the [integration record](phase-2/SINGLE_APK_INTEGRATION.md) and [architecture decision](DECISIONS.md#phase-2-packaging-correction--2026-10-28).

The `0.7.0-airplay-app-video-preview` candidate adds sender takeover and experimental in-app HLS playback, plus grouped TV controls, per-setting explanations, actionable recovery, accurate media/discovery status and playback controls that appear on remote interaction. It separates **Background listening**, **Keep session connected**, **Background audio** and **Auto-open incoming video**; all four now default on (see *Receiver defaults* below). Open **AirPlay → Open receiver**, then choose **TVCompanion** on the sender. A retained connection can outlive playback without remaining discoverable; hidden audio requires both session retention and Background audio. Incoming video opens once when enabled and Android permits. Open/Stop controls remain in the app, with an ongoing foreground-service notification where the TV exposes it. See [audio/background implementation and pending device review](phase-2/AUDIO_AND_BACKGROUND.md), including Spotify AirPlay setup. The optional [lab](../experiments/airplay-lab/README.md) reuses the same runtime/engine and is not a production dependency.

The **0.6.0 Bardi color preview** adds six presets, hue/saturation/color-brightness steps and a return to white mode. New writes require supported fresh status and separate readback; color never turns power on implicitly. Local checks pass, and the user confirmed the requested color controls work on the lamp. See [profile, behavior and verification](BARDI_COLOR_CONTROL.md).

## Platform and boundary notes (from the former README)

Google TV is the primary orchestration host; iPhone, iPad and future Mac software are clients, not peer hubs. The planned AirPlay receiver will be bundled in the single TVCompanion APK and run in a dedicated `:airplay` process, owning discovery, decoding and rendering. Mac media will go directly to that receiver; Binder/AIDL carries control, status and lifecycle events only. AirPlay video is integrated in the same APK as a foreground-only preview. The initial [TV experiment](phase-2/TV_RECEIVER_EXPERIMENT.md) proved Mac extended desktop; the [integrated preview](phase-2/SINGLE_APK_INTEGRATION.md) is now installed and user-confirmed. The newer audio/background candidate is implemented but awaits device review.

`ios/` is the Apple application workspace name, not a claim of current macOS support. Android and Apple applications have independent builds, dependencies, signing, and releases. CleanYourTV remains an external launcher; TVCompanion does not depend on or modify it.

The [licensing assessment](LICENSE_COMPLIANCE.md) records proposed release obligations and unresolved ownership/dependency checks; the [receiver boundary review](phase-2/RECEIVER_BOUNDARY_REVIEW.md) proposes build-time replacement of the native receiver. Neither document adopts a license or implements AirPlay.

The latest AirPlay investigation candidate is local and **not installed** at the user's request. Sender replacement and YouTube in-app playback require device validation; Crunchyroll/protected-video support is not promised. See [implementation, limits and test matrix](phase-2/SESSION_AND_APP_VIDEO_REVIEW.md).
