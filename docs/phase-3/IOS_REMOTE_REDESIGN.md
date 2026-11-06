# iOS remote redesign work log

Branch: `feat/ios-remote-redesign`, based on `main` at `24adb53` (Phase 3 merged).
Handoff: `TVCOMPANION_IOS_REMOTE_UX_VOICE_HANDOFF.md`, dated 2026-11-06.

## Baseline

- `swift test --package-path ios/TVCompanionKit`: 12 tests passed.
- Generic iOS Simulator build with signing disabled: succeeded.
- No physical iPhone, iPad or TV was used for this baseline.
- Existing remote requires manual Connect after foregrounding, disables all keys while a command is sending, places keyboard controls in the main scroll view, and associates both Keychain PINs with the selected TV profile UUID.

## Plan

1. Preserve saved TV profile UUID for explicit address updates; avoid merging different discovered TVs by name alone.
2. Make saved-pairing reconnect automatic on foreground, expose connection failures, and serialize a bounded queue of rapid key presses.
3. Replace the scrolling remote with a compact, accessible iPhone layout and adaptive iPad layout; move keyboard into a sheet and keep notification sending intact.
4. Add feature-gated Remote v2 voice wire, microphone capture and session cleanup; validate with synthetic audio and compile-only builds.
5. Record physical-device outcomes separately. Google TV voice support and the reported iPhone/iPad re-pairing symptom require device evidence.

## Changes

- Explicit **Update saved TV address** preserves the profile UUID and its Keychain account names. Choosing a different host through ordinary discovery or manual setup creates a separate profile. Existing saved profile data remains readable without migration.
- Saved TV records are retained in a list; selecting another TV no longer makes the previous profile UUID inaccessible. The existing single `tv-profile` record is imported on first run.
- A paired TV reconnects on foregrounding. Connection, pairing, offline and TLS identity-change outcomes have distinct UI states. Transient failures get at most four delayed attempts (1, 2, 4 and 8 seconds); PIN entry and certificate changes are not retried.
- D-pad and text commands use an ordered 16-item queue. Commands are cleared on disconnect; a slow key send no longer disables the entire remote.
- The remote screen uses a compact D-pad and a width-based iPad split layout. Keyboard input is in a sheet. The notification composer retains its sender behavior and labels its existing alert kinds Normal and Urgent.
- Voice uses feature bit 8 and Remote v2 fields 30/31/32 through the existing pinned TLS channel. It waits up to two seconds for the TV session ID. Microphone input is copied into a bounded queue, converted outside the render callback with AVAudioConverter, and sent as 8 KiB PCM packets; the last packet is padded only at the end.
- Microphone permission is requested on the first Voice action. Capture ends on Stop, backgrounding, disconnect, queue overflow, a lost or unsuitable audio route, or the start of an interruption. Routine audio category and route-configuration changes do not end capture. No audio is stored or logged. Debug diagnostics include a local installation ID, client certificate fingerprint, profile UUID and connection stage, without PINs, keys or microphone data.

## Verification and device handoff

- Before changes: 12 `TVCompanionKit` tests passed; generic iOS Simulator build passed.
- After connection and voice wire changes: 15 `TVCompanionKit` tests passed; compile-only iOS device build passed. The new `TVCompanionAudio` package passed 2 synthetic conversion tests at 44.1 and 48 kHz. A compile-only unsigned iOS device build linked both packages successfully.
- `plutil -lint` passes for the Xcode project and Info.plist; `git diff --check` passes.
- The user requested that all UI and device testing be done personally. No redesigned app was installed on a physical device. No TV pairing, voice command, notification delivery or iPad layout acceptance is claimed.

| Device | OS / TV service version | Pairing and reconnect | Voice | UI |
| --- | --- | --- | --- | --- |
| Farhan's iPhone 12 Pro Max | Not recorded | User reports latest build verified; individual pairing/reconnect cases not recorded | User reports latest build verified; detailed command outcome not recorded | User reports latest build verified, including the app icon and Voice button styling |
| Hanwak's iPad Pro 11-inch (3rd generation) | Not recorded; unavailable during development | Not run | Not run | Not run |
| Coocaa / Skyworth Google TV | Firmware and Remote Service version not recorded | Not run | Not run | Not run |

The user installed and verified the latest iPhone build. The iPhone ↔ iPad alternating-pairing scenario from the handoff is still unrecorded. If a TV does not advertise VOICE or does not answer KEYCODE_SEARCH, the UI reports Voice unavailable or a bounded timeout; this is not evidence of Assistant compatibility.

### Remaining device evidence

- Whether iPhone and iPad retain separate accepted client identities after alternation and TV reboot.
- Whether the target TV advertises VOICE and accepts begin, PCM payload and end messages.
- Whether the assistant recognizes a short spoken command; whether a stopped or interrupted session leaves no active microphone.
- Actual D-pad responsiveness, keyboard delivery, notification receipt and layout at iPhone/iPad sizes and accessibility settings.

## User feedback and follow-up — 2026-11-06

The user reported that other controls worked, but Voice briefly appeared and then stopped; holding Voice did nothing. The original capture code stopped on every audio route notification. Apple's route reasons include category changes, which can occur while starting recording. Capture now stops for an interruption beginning or a lost/unsuitable route, while ignoring routine category and route-configuration notifications. This was a code-supported cause, not a confirmed device diagnosis.

Voice now supports tap-to-start/tap-to-stop and hold-to-talk after microphone permission has been granted. A first hold without permission starts the normal permission flow on release. A Power button sends Remote v2 key code 26 only when the TV negotiated POWER. It is disabled while disconnected or unsupported. Power and wake behavior on the target TV were not reported separately.

## User acceptance — 2026-11-06

The user reported that the latest iPhone build is done and verified after the Voice and Power follow-up, the iOS app icon update, and the Voice button styling change. This is user-reported device verification; the development agent did not install or run the app. The latest icon and styling change passed a compile-only unsigned iOS device build, and Xcode generated iPhone and iPad icon entries. No separate iPad acceptance, alternating-pairing result, TV wake result, or detailed voice-command result was supplied.
