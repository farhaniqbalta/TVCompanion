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
- The user requested that all UI and device testing be done personally. The development agent did not install or run the redesigned app. User-reported device acceptance is recorded below.

| Device | OS / TV service version | Pairing and reconnect | Voice | UI |
| --- | --- | --- | --- | --- |
| Farhan's iPhone 12 Pro Max | Not recorded | User reports closeout checks passed | User reports command and microphone cleanup checks passed | User reports icon and remote layout passed |
| Hanwak's iPad Pro 11-inch (3rd generation) | Not recorded | User reports alternating-pairing and reconnect checks passed | Not separately reported for iPad | User reports remote and keyboard checks passed |
| Coocaa / Skyworth Google TV | Firmware and Remote Service version not recorded | User reports pairing and reboot checks passed | User reports voice command check passed | User reports alert/alert2, reboot startup and regression checks passed |

The user reports the iPhone and iPad closeout checks passed, including alternating pairing. Specific OS, firmware and Remote Service versions and logs were not supplied. If a different TV does not advertise VOICE or does not answer KEYCODE_SEARCH, the UI reports Voice unavailable or a bounded timeout; this does not establish Assistant compatibility on that TV.

### Evidence limits

- The user reported a pass for the closeout checks, but did not provide OS/firmware versions, logs, screenshots or protocol traces.
- Client fingerprint stability, individual Remote v2 voice message stages and unusual error paths were not separately documented.
- Broader compatibility across other TVs, older notification receivers and accessibility settings was not established by this setup's acceptance.

## User feedback and follow-up — 2026-11-06

The user reported that other controls worked, but Voice briefly appeared and then stopped; holding Voice did nothing. The original capture code stopped on every audio route notification. Apple's route reasons include category changes, which can occur while starting recording. Capture now stops for an interruption beginning or a lost/unsuitable route, while ignoring routine category and route-configuration notifications. This was a code-supported cause, not a confirmed device diagnosis.

Voice now supports tap-to-start/tap-to-stop and hold-to-talk after microphone permission has been granted. A first hold without permission starts the normal permission flow on release. A Power button sends Remote v2 key code 26 only when the TV negotiated POWER. It is disabled while disconnected or unsupported. Power and wake behavior on the target TV were not reported separately.

## User acceptance — 2026-11-06

The user reported that the latest iPhone build is done and verified after the Voice and Power follow-up, the iOS app icon update, and the Voice button styling change. This is user-reported device verification; the development agent did not install or run the app. The latest icon and styling change passed a compile-only unsigned iOS device build, and Xcode generated iPhone and iPad icon entries. At that point, separate iPad and alternating-pairing results had not yet been supplied.

## Phase 3 closeout — 2026-11-06

The user subsequently reported that **all requested Phase 3 closeout checks passed**: iPhone/iPad pairing and alternation, reconnect and keyboard/remote controls, normal and urgent message delivery and TV presentation, opt-in reboot startup and combined Android regressions, and voice command/microphone cleanup. Phase 3 is accepted for the tested home setup. The user did not provide device software versions or detailed traces; the agent did not perform UI or device testing. TV wake behavior, older-receiver rejection and broader compatibility are not claimed by this acceptance.
