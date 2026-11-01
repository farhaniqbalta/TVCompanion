# Phase 3 — iPhone/iPad remote and notification sender

Status: implementation candidate on `feature/ios-remote-notifications`; device acceptance pending. The branch now includes the merged Phase 2 AirPlay work from `main`. The combined Android candidate is versionCode 20 (`0.8.0-phase3-preview`), higher than the installed Phase 2 versionCode 19. No Phase 3 TV or Apple-device installation has been performed.

## Scope

- Native SwiftUI app for iPhone and iPad. Google TV Remote v2 discovery/manual address, pairing, D-pad, Home/Back, volume/mute and basic media keys.
- A separate TVCompanion notification sender for `alert` and `alert2`, using the existing PIN-authenticated notification endpoint. It does not use the Google TV remote credential for messages.
- TV-side `alert2` uses the existing bounded notification intake and a large dismissible overlay. The existing compact alert path, source filtering, privacy modes, expiry and queue remain in place.
- Pure Swift protocol/value kit, app-owned network/Keychain adapters, view models and SwiftUI views. No Android command server, remote keyboard, voice, app launching, or Mac target is included in this phase.

## Acceptance path

1. Build the Android candidate and iOS app from this branch. Select an Apple development signing team for physical iPhone/iPad installation.
2. On the TV, start notification reception and allow its overlay. Pair the iPhone/iPad remote through Google TV's code flow. Check discovery and manual address paths, D-pad/Select, Home/Back, volume and playback keys, disconnect/reconnect, and background/foreground behavior.
3. Enter the TVCompanion notification PIN separately. Send a normal `alert` and an `alert2`; check receipt status, TV privacy/compact/full presentation, manual Dismiss/Back, timed dismissal and FIFO ordering. Check a muted source and an older receiver that rejects `alert2` without silent downgrade.
4. Recheck existing TV lamp controls, normal notifications, and AirPlay on the combined APK. Phase 2's reverse-sender takeover and iPad YouTube compatibility retain their separate open device checks; Phase 3 does not claim to resolve them.

The notification endpoint remains unencrypted LAN HTTP. Use non-sensitive test content on a trusted local network. A queued receipt does not prove that an overlay was visible over every foreground TV app. No release or App Store readiness is claimed by local builds.

## Local verification

The initial Phase 3 implementation passed the Android debug build/lint, 111 repository JVM tests, 11 Swift kit tests, and the iOS simulator build on 2026-10-30; see [verification history](../VERIFICATION.md). After merging current `main`, the combined Android APK build, focused notification/AirPlay/runtime/native tests and lint pass; the Swift kit's 11 tests and iOS simulator build pass. Physical pairing, TLS certificate behavior, notification delivery, and AirPlay coexistence remain unverified for this combined candidate.
