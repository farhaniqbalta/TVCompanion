# Phase 2 — audio and opt-in receiver availability

Date: 2026-10-29. Branch: `plan/phase-2-airplay`. Candidate: **0.5.0-airplay-audio-preview**, versionCode 6. Follows the user-accepted [single-APK video checkpoint](SINGLE_APK_INTEGRATION.md). Historical 0.5.0 checkpoint; the current candidate and superseding policy are recorded in the 0.5.2 section below. Audio was subsequently user-confirmed; background video remains under review. This is not a public-release compliance sign-off.

## Audio implementation

All audio stays in `:airplay`: direct sender networking -> native receiver -> local decoder -> AAudio device output. No compressed audio, PCM, frames or native handles cross AIDL. The pure contract reports only availability, capabilities, activity and typed issues.

- AAC-ELD and AAC-LC use Android MediaCodec. The platform software AAC implementations are tried before a generic platform decoder to support ELD without bundling another AAC library. Unsupported device/profile/output formats produce a nonfatal audio issue rather than claiming success or stopping working video.
- ALAC uses the checksum-pinned Apple reference decoder; 16-bit stereo PCM is also accepted. The supported path is 44.1 kHz stereo, with format/size checks. Other sample rates/channel layouts are not claimed.
- AAudio output uses shared-mode PCM16. Decoder/device operations run on one lazy worker; no audio worker is created merely for idle discovery. The compressed queue is capped at 32 packets, 16 KiB each, and decoded chunks are bounded. Overflow/stale audio is discarded rather than accumulating delay.
- Writes are bounded, hardware state is accessed on one worker, and a one-shot inactivity deadline releases decoder/device resources after audio stops arriving. Focus is abandoned on inactivity/end/shutdown. Permission/focus denial, decoder errors and route failures do not trigger a permanent retry loop.
- Android audio focus is requested for an actual audio stream. Transient loss mutes/flushes; regain resumes; ducking reduces only this receiver’s gain. Sender volume is applied locally, including mute; TV master volume is not changed.
- Audio and video use the same source-local/NTP-to-monotonic timestamp mapping and an 80 ms jitter allowance. Video is scheduled to the Surface using presentation timestamps; audio scheduling accounts for queued/hardware frames where timestamps are available. Discontinuities and late data have bounded handling. This is an implementation policy, **not measured latency or verified lip-sync** on the TV.

The native adapter is split into audio output, audio-format constants, shared playout clock, H.264 configuration handling and vendor/JNI orchestration. No new framework, plugin loader or event bus is introduced.

## Receiver lifetime independent of UI

The service owns networking and the engine. `ReceiverEngine.start(name, observer)` starts without a Surface; `attachSurface(surface or null)` independently attaches/detaches Android-local rendering. Activity destruction no longer inherently destroys a user-kept receiver.

**Default:** Keep available is off. Opening playback starts the receiver; leaving playback shuts it down with the existing asynchronous cleanup rules.

**Opt-in:** the AirPlay settings screen offers Keep available after leaving. Enabling it starts the receiver without a playback Activity and promotes the service with an ongoing notification. The option applies to the current service lifetime; it resets after process/app restart or reboot. No boot receiver, sticky restart, alarm, scheduled polling or automatic opt-in is added. Stop in settings or the notification turns the option off and releases the engine. Disabling it while playback is visible lets foreground playback continue until that screen leaves.

The ongoing notification provides Open receiver and Stop. Idle networking uses the `connectedDevice` foreground-service type, whose existing `CHANGE_WIFI_MULTICAST_STATE` prerequisite is also genuinely used by discovery. Active media adds `mediaPlayback`; the corresponding Android permissions are declared. The product requests/enforces notification permission and channel availability for background opt-in; TV firmware may not expose a usable ongoing-notification UI, so app controls remain available. The settings screen requests POST_NOTIFICATIONS when necessary and reports denial. This is an application policy, not a claim that Android always requires that permission to start any FGS. [Android foreground-service types and prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types).

The initial 0.5.0 flow required manually opening playback for background video. User review confirmed audio worked and video appeared when the receiver was manually opened, but the TV did not expose a useful background notification. The 0.5.1 correction opens playback once for an incoming video session when Android allows it. On the validated Android 14 TV this uses the already user-granted SYSTEM_ALERT_WINDOW exception; there is no new permission grant or background-launch workaround. Restricted/newer platforms retain manual Open receiver in the app and notification as available fallbacks. Audio-only sessions do not open a screen, and Back is not immediately undone for an existing session.

The receiver keeps only bounded SPS/PPS codec configuration while no display is attached and drops video frames. The existing connection attaches a Surface when playback opens. It now feeds valid non-IDR/intra-refresh frames to the decoder like the upstream continuous-stream renderer; it no longer waits indefinitely for a type-5 IDR NAL. Audio may play while the screen is elsewhere, subject to audio focus. Rendering surfaces and media remain local; this is not a Surface-transfer IPC design.

AIDL protocol version 2 adds a focused availability command and status flags for kept availability/audio activity. Commands remain bounded and control-only. The pure lifecycle owner handles background retention, display loss/reattachment, explicit stop, disable-while-active and nonfatal audio failure. Existing native teardown/generation checks prevent late callbacks from resurrecting a stopped session.

## Dependencies and licensing

ALAC source pin: `macosforge/alac` **c38887c5c5e64a4b31108733bd79ca9b2496d987**. Archive SHA256: `98635ece42fb1c3fceb75eaa4b5164d866e09f0195b3e7ec4085f1123c5e272f`. Its inspected license/header grant is Apache-2.0, copyright Apple Inc. The script retains source/notices in ignored `.native/` and includes its license/attribution in generated app notices. The encoder is used only for a deterministic host-side round-trip test and is not linked into the APK. [Pinned ALAC source and license](https://github.com/macosforge/alac/tree/c38887c5c5e64a4b31108733bd79ca9b2496d987).

GStreamer/FFmpeg are not added. No license file or license choice for independently authored code is added or changed. Removing/replacing the receiver still removes its native decoder/dependency graph through composition. The combined distribution’s GPL obligations and the broader [compliance review](../LICENSE_COMPLIANCE.md) remain applicable. The age/security characteristics of reference/native decoders and the actual distribution channel remain release-review items, not established by a successful build.

## Recorded software verification

- Main APK builds; JVM suites pass **81 tests**, including background/no-surface startup, retain-on-detach, explicit stop clearing opt-in, disable with/without display, nonfatal audio failures, audio focus state and control metadata flags.
- Native host checks pass: shared playout-clock bounds/discontinuities, bounded H.264 configuration/IDR handling and a real stereo ALAC packet encoded by the reference encoder then decoded using the production decoder configuration, with exact PCM comparison.
- Lint has zero errors and one known target-specific ChromeOS ABI warning. This remains a 32-bit ARM preview for the validated TV.
- Native preparation, license assets and CI include the new pinned dependency and host checks. No APK/native binary is added to Git or uploaded by the report-only CI workflow.
- No CPU/RAM benchmark, intentional device crash, lamp write, notification injection or automated AirPlay session test is part of this change. At the user’s request, device behavior is left for user review after installation.

## User review and remaining limits

Try screen-mirroring sound, sender volume/mute, TV audio focus when another app plays, and audio-only AirPlay. For background mode, enable Keep available, leave playback, connect from the sender, and verify incoming video opens playback automatically on this TV. Manual Open receiver remains the fallback. Stop should remove availability. The setting intentionally does not auto-start after reboot/app restart. Actual codec support, audible output, route behavior, A/V synchronization, background notification UX and reconnect/keyframe timing remain hardware observations, not claims derived from unit tests.

Installation/review outcome is recorded below after the authorized update; user acceptance is not assumed from installation alone.

## Authorized installation

The 0.5.0 candidate was installed successfully as an update with the same application identity/signing arrangement. Existing preference files were verified byte-for-byte preserved. The notification receiver was not running before this update. The dashboard was opened; no AirPlay session or kept-availability mode was started automatically. Device playback/background review is left to the user, as requested. ADB closure remains pending while user testing continues.

Installed APK SHA256: `e55ea5a79d027f3e634f440e2a5a9feb3654e3db070aec2832a87412a2b7f928`.

## Background video flow correction — 0.5.1

User review: audio works; with background mode enabled, video appears when playback is manually opened. The missing automatic presentation step was therefore a flow problem, not evidence that the media path could not decode video.

Correct flow: discover -> pair/negotiate -> audio-only remains background, while a video session requests playback -> open once if Android permits -> attach the existing local surface -> render. The service and networking remain alive independently. Default-off behavior and Stop controls are unchanged. The H.264 parameter cache was also corrected so it cannot be misused as an IDR-only admission gate. Software checks cover one launch per connection, platform denial, not undoing Back, and configuration from a non-IDR stream. Actual automatic presentation is left for user review after the corrective update. [Android background Activity launch exceptions](https://developer.android.com/guide/components/activities/background-starts).

The 0.5.1 corrective APK (versionCode 7) was installed successfully with existing preferences preserved. Software verification passed 84 JVM tests and native helper checks; lint reports zero errors and the known ChromeOS ABI warning. The dashboard was opened without starting a receiver session. Automatic video presentation awaits user review. ADB closure remains pending during testing.

Installed APK SHA256: `67db56ffb3fd5f974c965995c4b1a56f6e773e420e5fa710c735bbab1abd3efc`.

## Separate background policies and startup video — 0.5.2

Candidate **0.5.2-airplay-lifecycle-preview**, versionCode 8, supersedes the bundled Keep available behavior above. User review of 0.5.1 confirmed automatic playback-window opening but reported audio without video. Earlier logs showed input reaching the AVC decoder without first output for background-started attempts, while a foreground attempt produced output. This supports investigating startup references/configuration and Surface timing; it does not establish a verified root cause or hardware fix.

| Control | Default | Responsibility |
| --- | --- | --- |
| Background listening | Off | Advertise availability when playback is closed; enabling explicitly starts the idle receiver. |
| Keep session connected | On | Retain an existing connection after playback leaves; this setting alone never starts an idle engine. |
| Background audio | Off | Permit sound from a retained connected session while playback is hidden; foreground sound still follows audio focus. |
| Auto-open incoming video | On | Request playback once for incoming video where Android allows; audio-only never opens a window. |

These are independent policies with real dependencies: background audio requires session retention, and automatic video opening is subject to Android launch rules. With listening off, a hidden retained session withdraws AirPlay/RAOP advertisements without closing its existing sockets. Advertisement withdrawal is not an access-control mechanism; sender caches can briefly show stale discovery. If retention is off when playback leaves, the service closes the session. If idle listening is still enabled it creates a fresh receiver only after the old engine releases its resources. On final sender disconnect, a hidden receiver without listening shuts down. Stop ends the session and disables listening. Options apply until the service exits; no reboot/process-restart startup or permanent native process is introduced.

The control protocol is now **v3**, with bounded flags for the independent settings. The pure lifecycle owner determines discovery, retention and audio eligibility; Android adapters translate them into local receiver operations. Android/JNI/vendor types remain outside domain, and no media crosses IPC. Repeated attachment of the same Surface no longer recreates the decoder unnecessarily.

Native video configuration now waits until SPS/PPS are available. While no Surface exists, a receiver-local startup buffer retains at most **2 MiB, 60 frames, or one second** of compressed data, whichever is reached first. Exceeding a limit clears the buffer and stops accumulating until the next attachment/reset. On attachment the ordered startup data primes decoder references before live frames; stale output is discarded through the normal clock policy. The buffer is cleared on attachment and session reset. This addresses initial reference-frame loss during Activity launch without unbounded buffering or decoding an entire hidden session. A long-hidden connection may still need sender intra-refresh/keyframe recovery after reattachment; device validation remains pending.

Software verification: APK build, **89 JVM tests** with zero failures/errors (including the existing Phase 1 suites), native clock/H.264/preroll/ALAC checks, and lint with zero errors plus the existing ChromeOS ABI warning. The user subsequently reported no TV access: **0.5.2 has not been installed or device-tested**. Earlier installation records above apply only to those earlier candidates. No additional TV connection, AirPlay session, lamp operation, notification injection or benchmark was performed after that instruction. ADB closure from the earlier testing session remains unverified while the TV is unavailable. No commit/push is included.

### Spotify AirPlay review

Reviewed 2026-10-29. Spotify lists Apple AirPlay separately from Spotify Connect among its supported speaker routes: [Spotify on speakers](https://support.spotify.com/us/article/spotify-on-speakers/). Apple documents selecting an audio destination from Control Center/Lock Screen or an app: [AirPlay audio routing](https://support.apple.com/en-gb/105068). Spotify Connect controls playback on a separate Spotify-enabled device: [Spotify Connect](https://support.spotify.com/us/article/spotify-connect/). TVCompanion currently implements an AirPlay receiver, not a Spotify Connect endpoint.

The pinned UxPlay README describes standalone ALAC AirPlay audio as well as AAC mirror audio. The Android adapter implements ALAC, supported AAC profiles and PCM locally at 44.1 kHz stereo, with user-controlled background permission and focus. **Inference:** Spotify routed through the sender's AirPlay audio output can use this generic path without an app-specific SDK, API key or account on the TV. Actual Spotify negotiation and playback with the installed sender versions remain unverified; do not promise a particular AirPlay generation, codec, multiroom mode or lossless Spotify source quality from the receiver's ALAC support alone.

When a TV is available:

1. For screen-free music, enable Background listening, Keep session connected and Background audio. Auto-open video can stay on: audio-only does not trigger it.
2. On iPhone/iPad, play Spotify, then select TVCompanion in the system AirPlay **audio** picker in Control Center; do not use Screen Mirroring for this check. If Spotify exposes an AirPlay picker, that route can be checked separately.
3. On Mac, select TVCompanion as the system AirPlay sound output, then play Spotify. This is a system audio route, so it is not inherently limited to Spotify.
4. Confirm audio while the AirPlay screen is closed, no playback-window launch for music alone, volume/mute, pause/resume/next track, leaving another TV app foregrounded, focus loss/regain and explicit Stop. Compare a second audio app to isolate sender-specific problems.
5. Separately reconnect with Screen Mirroring/extended desktop and verify automatic picture presentation and surface reattachment. Record actual sender/OS versions when testing; none are inferred here.

Track metadata, artwork and TV-side transport controls are not implemented by the current adapter, whose callbacks intentionally discard metadata. No Spotify SDK, Connect implementation, credentials, new dependency or license change is added for this review.

## TV UI/UX refresh — 0.5.3

Candidate **0.5.3-airplay-ui-preview**, versionCode 9, follows the locally verified 0.5.2 lifecycle candidate. Receiver defaults and native media behavior are unchanged. The settings page separates receiver actions, sender instructions, background preferences and preview/license details. Wide screens use two columns, with stacked scrolling content for narrow/large-text configurations. Setting descriptions explain retention, hidden audio and Android-limited automatic presentation. A stored Background audio On choice is explicitly inactive when retention is disabled, and cannot be toggled until retention is enabled.

A feature-owned, pure presentation mapper selects status without Android or native dependencies. Protocol **v4** adds discovery and video-output facts to control/status flags. The lifecycle owner clears video activity on display loss/stop and updates discovery facts independently. Audio activity cannot make either screen claim video is displayed. Attaching a display clears a stale display-required issue without inventing video output.

The playback waiting screen provides sender instructions and retry/leave/stop actions. Back is described as leaving playback, with policy-specific retention/audio hints. Routine video status is hidden. Remote D-pad/OK reveals controls; the first key reveals rather than immediately activating a button. A single six-second callback hides the toolbar, except during touch exploration; callbacks are cancelled on departure. No polling, permanent animation or new framework is introduced. Fatal errors expose retry, disconnected controls offer rebind, and notification denial exposes app notification settings. Runtime and settings strings have separate prefixes to prevent resource merging from replacing each other's labels.

Local verification: APK builds; **95 JVM tests** pass, including pure UI-state mapping, lifecycle discovery/video facts, all flag combinations and existing Phase 1 suites. Lint has zero errors and the existing ChromeOS ABI warning. The user has no TV access, so **this APK is not installed**. Device focus paths, screenshots/overscan, large text, video toolbar behavior, Spotify audio and the 0.5.2 media recovery remain deferred for home testing. No TV connection or benchmark was attempted for this refresh.

## Authorized AirPlay home test — build 11

The user returned to `plan/phase-2-airplay` after accepting the Bardi color branch and requested testing. The existing AirPlay UI candidate retains versionName `0.5.3-airplay-ui-preview`; versionCode is raised from 9 to **11** solely to update the installed Bardi build (10) in place. No receiver/UI behavior changed for this build-number adjustment. The Bardi color implementation remains on its separate branch and is not included in this AirPlay checkout.

A fresh build, all **95 JVM tests**, native media helper checks and lint passed (zero errors; existing ChromeOS ABI warning). The APK was installed successfully using the existing application identity. Existing preference files were verified byte-for-byte preserved; the notification receiver was not previously running. The dashboard was opened. No AirPlay stream, background listening or lamp command was initiated by the agent. Actual video recovery, remote controls, background behavior and Spotify audio remain pending user feedback; the software checks do not establish those device outcomes. ADB closure remains pending during user testing.

Installed APK SHA256: `b341aba63b522efb993d7cfcaa149a857e9765964f7662520cb56c8befddb01d`.

## Home feedback and Spotify diagnostics — build 12

The user confirmed iPad video/pictures, iPad screen mirroring and Mac extended desktop work on the home-test candidate. Spotify AirPlay remains silent, with both Keep session connected and Background audio confirmed On. Do not treat the video acceptance as audio-only acceptance.

Read-only allowlisted TV logs showed successful video output and audio-device activity for an earlier mirroring session, then a separate setup/record exchange without a new audio-device opening. A later read-only audio-focus inspection had no TVCompanion focus entry. These observations do not distinguish transport/format negotiation, lifecycle/focus and decoding, and do not prove the root cause.

VersionCode **12**, `0.5.4-airplay-audio-diagnostics`, adds focused diagnostics: codec category and bounded frame count on negotiation, first audio input and policy eligibility, focus grant/denial, first ALAC decode outcome, first audio-device write, sender mute-state changes, and fixed labels for unsupported stream types/ANNOUNCE. No payload, track metadata, sender identifiers, keys or raw upstream logs are forwarded. Events are per format/transition rather than per-packet logging. The working video path is unchanged. This is a diagnostic candidate, not a claim that Spotify is fixed.

Build and focused runtime/presentation/IPC tests pass; lint has zero errors and the existing ABI warning. The update installed with all existing preference files preserved byte-for-byte; the notification receiver was not previously running. Only the dashboard was opened; the agent did not start an AirPlay session, change background options or send lamp commands. Receiver options reset with service restart as documented. A fresh user-started Spotify audio connection is needed to capture the diagnostic stages. ADB closure remains pending during testing.

Installed APK SHA256: `bea226da66eece1c1b0c58c0fb9083c3e55e27c9341074e7473d36a18f30a7c2`.

## Spotify ALAC byte-order correction — build 13

A fresh user-started Spotify attempt on build 12 produced the following allowlisted sequence: ALAC format, 352 frames; audio policy enabled; focus granted; first audio input allowed; audio-output initialization failed. No audio-device open or ALAC-decode event followed. This rules out discovery, missing media packets and initial focus denial for that observed attempt; it does not itself identify every possible output error.

Inspection found a concrete target portability defect: pinned ALAC `EndianPortable.c` auto-detects little-endian x86/Windows, but not ARM. The Android ALAC target had no explicit byte-order definition, causing network-order configuration words to be misread on little-endian ARM and potentially requesting very large decoder buffers. This is consistent with initialization failing before an AAudio open on the TV. A host encoder/decoder round-trip alone was insufficient target-portability evidence.

Build **13**, `0.5.5-airplay-alac-fix`, defines the ALAC target byte order from CMake's target byte-order detection, failing configuration if unknown. The upstream source pin and notices are unchanged. A production guard verifies the frame-length wire conversion before calling upstream allocation, then checks initialized frame length, 44.1 kHz rate, 16-bit depth and stereo channels. Host checks now use explicit host byte order and independently verify fixed wire/configuration values in addition to PCM round-trip. The generated ARM ALAC compiler command was checked for the little-endian definition. Native checks, APK build, focused JVM suites and lint passed; the existing ABI warning remains.

The correction was installed as an update, with existing preference files preserved byte-for-byte. The notification receiver was not previously running; only the dashboard was opened. No sender playback, background option change or lamp command was initiated by the agent. A fresh Spotify connection is required after re-enabling service-lifetime options. Spotify audible recovery is **pending user verification**; iPad video/mirroring and Mac extended desktop remain previously user-confirmed. ADB closure remains pending during testing.

Installed APK SHA256: `333590dc67f13b670583a6e82fd18f0670e493a53c67e159b3af8df2e974f507`.

## User acceptance after build 13

The user reported “all works fine” after the ALAC correction and requested Spotify reconnection. Spotify AirPlay audio is therefore accepted by user report on the current TV/sender setup. Combined with earlier feedback, accepted paths now include iPad video/pictures, iPad screen mirroring and Mac extended desktop. This does not establish exhaustive codec/device compatibility, long-session stability, measured synchronization/latency, multiroom behavior or all audio-focus edge cases. No benchmark or additional device action was performed to record acceptance. ADB closure remains unverified. The correction and acceptance record remain local until a push is requested.
