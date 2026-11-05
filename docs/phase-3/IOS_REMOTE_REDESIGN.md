# iOS remote redesign work log

Branch: `feat/ios-remote-redesign`, based on `main` at `24adb53` (Phase 3 merged).
Handoff: `TVCOMPANION_IOS_REMOTE_UX_VOICE_HANDOFF.md`, dated 2026-11-06.

## Baseline

- `swift test --package-path ios/TVCompanionKit`: 12 tests passed.
- Generic iOS Simulator build with signing disabled: succeeded.
- No physical iPhone, iPad or TV was connected for this baseline.
- Existing remote requires manual Connect after foregrounding, disables all keys while a command is sending, places keyboard controls in the main scroll view, and associates both Keychain PINs with the selected TV profile UUID.

## Plan

1. Preserve saved TV profile UUID for explicit address updates; avoid merging different discovered TVs by name alone.
2. Make saved-pairing reconnect automatic on foreground, expose connection failures, and serialize a bounded queue of rapid key presses.
3. Replace the scrolling remote with a compact, accessible iPhone layout and adaptive iPad layout; move keyboard into a sheet and keep notification sending intact.
4. Add feature-gated Remote v2 voice wire, microphone capture and session cleanup; validate with synthetic audio and simulator builds.
5. Record physical-device outcomes separately. Google TV voice support and the reported iPhone/iPad re-pairing symptom require device evidence.

## Changes

- Explicit **Update saved TV address** preserves the profile UUID and its Keychain account names. Choosing a different host through ordinary discovery or manual setup creates a separate profile. Existing saved profile data remains readable without migration.
