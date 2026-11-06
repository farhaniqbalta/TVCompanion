# Phase 3 — iPhone/iPad remote and notification sender

Status: **accepted for the tested home setup**, by user report on 2026-11-06. The Phase 3 implementation and subsequent iOS remote redesign are merged into `main`. The combined Android candidate is versionCode 21 (`0.8.1-phase3-boot-preview`); device and TV service versions were not supplied. This is Phase 3 acceptance, not a general compatibility or store-release claim.

Notifications and AirPlay each have a **Start after TV reboot** choice, initially off. Notification startup checks TV notifications and overlay permission. AirPlay startup restores Background listening, Keep session connected and Background audio, using a connected-device foreground service while idle; it does not restore a previous sender connection or start a media surface. Android may decline foreground startup if required permissions or platform conditions are missing; the feature screens remain the manual recovery path. The user reports the reboot-start checks passed on the tested TV.

## Scope

- Native SwiftUI app for iPhone and iPad. Google TV Remote v2 discovery/manual address, pairing, D-pad, Home/Back, volume/mute, basic media keys, and focused TV text entry with Delete/Enter controls.
- A separate TVCompanion notification sender for `alert` and `alert2`, using the existing PIN-authenticated notification endpoint. It does not use the Google TV remote credential for messages.
- TV-side `alert2` uses the existing bounded notification intake and a large dismissible overlay. The existing compact alert path, source filtering, privacy modes, expiry and queue remain in place.
- Pure Swift protocol/value kit, app-owned network/Keychain adapters, view models and SwiftUI views. The subsequent iOS redesign added feature-gated voice and Power controls. No Android command server, app launching, or Mac target is included in this phase.

## Acceptance path

1. Use the merged Android candidate and iOS app. Select an Apple development signing team for physical iPhone/iPad installation.
2. On the TV, start notification reception and allow its overlay. Pair the iPhone/iPad remote through Google TV's code flow. Check discovery and manual address paths, D-pad/Select, Home/Back, volume and playback keys, disconnect/reconnect, and background/foreground behavior.
3. Enter the TVCompanion notification PIN separately. Send a normal `alert` and an `alert2`; check receipt status, TV privacy/compact/full presentation, manual Dismiss/Back, timed dismissal and FIFO ordering. Check a muted source. If an older receiver is available, confirm that it rejects `alert2` without silent downgrade.
4. Recheck existing TV lamp controls, normal notifications, and AirPlay on the combined APK. Phase 2's reverse-sender takeover and iPad YouTube compatibility retain their separate open device checks; Phase 3 does not claim to resolve them.

The notification endpoint remains unencrypted LAN HTTP. Use non-sensitive test content on a trusted local network. A queued receipt does not prove that an overlay was visible over every foreground TV app. No release or App Store readiness is claimed by local builds.

## Local verification

The initial Phase 3 implementation passed the Android debug build/lint, 111 repository JVM tests, 11 Swift kit tests, and the iOS simulator build on 2026-10-30; see [verification history](../VERIFICATION.md). After merging Phase 2, the combined Android APK build, focused notification/AirPlay/runtime/native tests and lint passed. The redesign later passed 15 Swift kit tests, 2 synthetic audio tests, and a compile-only unsigned iOS device build. These local checks are separate from the user's device acceptance below.

## Device acceptance — 2026-11-06

After the merged iOS redesign, the user reported that **all Phase 3 closeout checks passed** on their setup:

- iPhone and iPad pairing, iPhone → iPad → iPhone switching, reconnect after backgrounding, relaunch, Wi-Fi return and TV reboot, remote keys, and focused TV keyboard input.
- Normal `alert` and urgent `alert2` delivery with the separate notification PIN, TV display, dismissal, expiry, ordering and privacy behavior.
- Notification and AirPlay opt-in reboot startup, plus regression checks for lamp controls, normal notifications and AirPlay on the combined Android build.
- Voice command and microphone cleanup after Stop, backgrounding and interruption.

This is a user-reported pass for the tested devices; OS, firmware and Google TV Remote Service versions, logs and screenshots were not provided. The agent did not install or test the UI. Older-receiver `alert2` rejection, unusual TLS/certificate failures, broader device compatibility and App Store readiness are outside this acceptance report. Phase 2's reverse-sender takeover and iPad YouTube compatibility remain separately scoped.
