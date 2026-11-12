# Graph Report - TVCompanion  (2026-11-12)

## Corpus Check
- 155 files · ~75,142 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 52 file(s) not represented in the graph (top: .xml 36, .patch 5, .properties 2)
- WARNING: 52 of 207 files seen (25%) have no supported extractor and are missing from this graph entirely. It may look complete while representing only part of the codebase.

## Summary
- 1804 nodes · 3486 edges · 99 communities (56 shown, 43 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 293 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Android dashboard lifecycle
- Remote identity encoding
- Tuya light operations
- Notification HTTP sessions
- AirPlay issue states
- Notification application control
- Native video configuration
- Voice PCM encoding
- Project contracts and compliance
- Native receiver state
- Native receiver callbacks
- Notification event queue
- Device provisioning scripts
- Android verification workflow
- Notification settings screen
- AirPlay service lifecycle
- Pull request workflow
- iOS TV profile selection
- Android settings runtime
- UxPlay engine control
- HLS playback lifecycle
- TLS connection lifecycle
- iOS application composition
- Google TV pairing
- AirPlay playback activity
- Native audio lifecycle
- Automation check workflow
- Tuya encrypted transport
- Notification delivery contract
- Native audio decoding
- TV notification cards
- iOS verification workflow
- Phase one architecture
- Single APK receiver design
- iOS infrastructure imports
- AirPlay receiver domain
- JNI receiver interface
- iOS remote interface
- Remote key commands
- Application engine composition
- Receiver event states
- Voice capture lifecycle
- Pull request descriptions
- TV design components
- Receiver engine interface
- Native receiver signals
- iOS remote redesign
- Remote view model
- Tuya credential storage
- AirPlay presentation states
- Android AirPlay control
- Notification receiver service
- Notification overlay rendering
- Absolute deadline tests
- AirPlay audio architecture
- AirPlay wire phases
- Tuya authentication tests
- AirPlay wire tests
- Hardware acceptance limits
- iOS pairing interface
- AirPlay presentation tests
- Video presentation policy
- Tuya deadline reader
- HLS progress policy
- Receiver integration boundaries
- iOS credential keychain
- AirPlay lab activity
- Sender session replacement
- Remote connection states
- Remote voice states
- Boot startup handling
- Urgent notification privacy
- Native media delivery
- iOS icon generation
- Voice control gestures
- Android dashboard design
- TV launcher banner
- Swift package manifests
- TV brand mark
- iOS application icon

## God Nodes (most connected - your core abstractions)
1. `Receiver` - 45 edges
2. `ReceiverSession` - 40 edges
3. `UxPlayReceiverEngine` - 38 edges
4. `RemoteViewModel` - 36 edges
5. `TVProfile` - 33 edges
6. `ProtoMessage` - 30 edges
7. `NotificationSettingsActivity` - 29 edges
8. `GoogleTVRemote` - 29 edges
9. `Engine` - 28 edges
10. `RemoteKey` - 28 edges

## Surprising Connections (you probably didn't know these)
- `Application engine composition` --references--> `TVCompanionApplication`  [EXTRACTED]
  docs/phase-2/SINGLE_APK_INTEGRATION.md → android/app/src/main/java/id/poncol/tvcompanion/app/TVCompanionApplication.kt
- `Screen owned lamp operations` --references--> `LightController`  [EXTRACTED]
  docs/phase-1/CURRENT_ARCHITECTURE.md → android/core/domain/src/main/java/id/poncol/tvcompanion/application/LightController.kt
- `Neutral receiver semantics` --references--> `AirPlayReceiverControl`  [EXTRACTED]
  docs/phase-2/RECEIVER_BOUNDARY_REVIEW.md → android/core/domain/src/main/java/id/poncol/tvcompanion/domain/airplay/AirPlayReceiver.kt
- `Local Surface attachment` --references--> `ReceiverEngine`  [EXTRACTED]
  docs/phase-2/RECEIVER_BOUNDARY_REVIEW.md → android/integrations/airplay-runtime/src/main/java/id/poncol/tvcompanion/airplay/runtime/ReceiverEngine.java
- `Platform MediaPlayer HLS` --references--> `HlsPlayback`  [EXTRACTED]
  docs/phase-2/SESSION_AND_APP_VIDEO_REVIEW.md → android/integrations/uxplay-native/src/main/java/id/poncol/tvcompanion/uxplay/HlsPlayback.kt

## Import Cycles
- None detected.

## Communities (99 total, 43 thin omitted)

### Community 0 - "Android dashboard lifecycle"
Cohesion: 0.05
Nodes (19): MainActivity, LightController, State, Brightness, Color, LightColor, LightCommand, LightGateway (+11 more)

### Community 1 - "Remote identity encoding"
Cohesion: 0.07
Nodes (19): RemoteIdentityStore, DER, PairingWire, FrameDecoder, ProtoMessage, .encoded, RemoteCommand, key (+11 more)

### Community 2 - "Tuya light operations"
Cohesion: 0.07
Nodes (8): TuyaColorCodec, LampSession, TuyaLightGateway, Session, TuyaColorTest, Session, TuyaLightGatewayTest, LampSession

### Community 3 - "Notification HTTP sessions"
Cohesion: 0.08
Nodes (10): LocalServer, NotificationListener, NotificationOverlay, ReceiverSession, Fixture, Listener, Overlay, Preferences (+2 more)

### Community 4 - "AirPlay issue states"
Cohesion: 0.10
Nodes (17): AirPlayIssue, AUDIO_UNAVAILABLE, BUSY, CLOSED, DISPLAY_REQUIRED, MEDIA_FAILED, MEDIA_UNSUPPORTED, PROTECTED_CONTENT (+9 more)

### Community 5 - "Notification application control"
Cohesion: 0.06
Nodes (19): NotificationController, NotificationPreferences, NotificationRuntime, ReceiverIssue, LISTENER_FAILED, OVERLAY_FAILED, OVERLAY_PERMISSION, START_FAILED (+11 more)

### Community 6 - "Native video configuration"
Cohesion: 0.07
Nodes (14): alacCookie(), H264Config, pps, sps, expired(), presentationTime(), Packet, bytes (+6 more)

### Community 7 - "Voice PCM encoding"
Cohesion: 0.05
Nodes (27): AVFoundation, VoiceCaptureFailure, interrupted, VoiceAudioError, conversionFailed, invalidFormat, VoicePCMEncoder, VoicePCMEncoderTests (+19 more)

### Community 8 - "Project contracts and compliance"
Cohesion: 0.07
Nodes (32): Apple ALAC decoder, OpenSSL libcrypto dependency, libplist static dependency, UxPlay native CMake composition, UxPlay receiver library, Standard-library brand generation, Scoped secure command authorization, Shared device operation serialization (+24 more)

### Community 9 - "Native receiver state"
Cohesion: 0.05
Nodes (26): Receiver, advertised, audio, closing, config, connections, decoder, discovery (+18 more)

### Community 10 - "Native receiver callbacks"
Cohesion: 0.06
Nodes (3): hlsPlaybackInfo(), hlsPlaylistRemove(), reset()

### Community 11 - "Notification event queue"
Cohesion: 0.11
Nodes (6): EventQueue, NotificationEvent, EventQueueTest, EventGate, Result, EventGateTest

### Community 12 - "Device provisioning scripts"
Cohesion: 0.08
Nodes (3): main(), fetch(), main()

### Community 13 - "Android verification workflow"
Cohesion: 0.06
Nodes (33): concurrency, cancel-in-progress, group, run, jobs, verify, name, on (+25 more)

### Community 14 - "Notification settings screen"
Cohesion: 0.12
Nodes (3): NotificationSettingsActivity, Screen, ReceiverStatusText

### Community 15 - "AirPlay service lifecycle"
Cohesion: 0.12
Nodes (3): AirPlayService, Lease, ReceiverBinder

### Community 16 - "Pull request workflow"
Cohesion: 0.06
Nodes (30): concurrency, cancel-in-progress, group, if, permissions, runs-on, steps, timeout-minutes (+22 more)

### Community 17 - "iOS TV profile selection"
Cohesion: 0.10
Nodes (4): .body, TVDiscovery, TVProfile, .notificationURL

### Community 18 - "Android settings runtime"
Cohesion: 0.09
Nodes (6): AirPlaySettingsActivity, onCreate(), AndroidNotificationRuntime, ReceiverSettings, NotificationThemes, Theme

### Community 20 - "HLS playback lifecycle"
Cohesion: 0.19
Nodes (3): HlsPlayback, Listener, HlsProgress

### Community 21 - "TLS connection lifecycle"
Cohesion: 0.15
Nodes (6): ChannelTimer, OneShot, PeerEvidence, TLSChannel, .serverRSA, Network

### Community 22 - "iOS application composition"
Cohesion: 0.12
Nodes (8): CompanionApp, .body, TVStore, NotificationSenderView, .body, NotificationViewModel, NotificationHTTPClient, SwiftUI

### Community 25 - "Native audio lifecycle"
Cohesion: 0.08
Nodes (21): AudioOutput::AudioOutput(), impl, allowed, broken, closing, decodeReported, duck, gain (+13 more)

### Community 26 - "Automation check workflow"
Cohesion: 0.08
Nodes (23): concurrency, cancel-in-progress, group, jobs, test-description, name, on, pull_request (+15 more)

### Community 28 - "Notification delivery contract"
Cohesion: 0.13
Nodes (9): DeliveryReceipt, duplicate, filtered, queued, NotificationEnvelope, NotificationKind, alert, alert2 (+1 more)

### Community 29 - "Native audio decoding"
Cohesion: 0.13
Nodes (3): AudioOutput, event, nowNs()

### Community 31 - "iOS verification workflow"
Cohesion: 0.10
Nodes (20): concurrency, cancel-in-progress, group, jobs, verify, name, on, pull_request (+12 more)

### Community 32 - "Phase one architecture"
Cohesion: 0.12
Nodes (20): Bounded notification ingress, Phase 1 architecture assessment, Plaintext notification PIN, Pure Kotlin domain, Screen owned lamp operations, Targeted refactor, Future contracts only, Gateway cancellation (+12 more)

### Community 33 - "Single APK receiver design"
Cohesion: 0.12
Nodes (20): Separate receiver APK superseded, Receiver local audio, Control only IPC, Distribution license gate, One production APK, Phase 2 single APK plan, Private airplay process, Runtime engine separation (+12 more)

### Community 34 - "iOS infrastructure imports"
Cohesion: 0.15
Nodes (9): CryptoKit, Darwin, Foundation, NoRedirects, os, Security, TVCompanionAudio, TVCompanionKit (+1 more)

### Community 35 - "AirPlay receiver domain"
Cohesion: 0.12
Nodes (10): AirPlayAvailability, AVAILABLE, DISCONNECTED, UNSUPPORTED, AirPlayConnection, CONNECTING, NONE, PLAYING (+2 more)

### Community 36 - "JNI receiver interface"
Cohesion: 0.25
Nodes (10): codec(), configureVideo(), destroy(), Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeHlsEnded(), Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeHlsReleased(), Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeSetDiscoverable(), Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeSetFocus(), Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeSetSurface() (+2 more)

### Community 37 - "iOS remote interface"
Cohesion: 0.22
Nodes (8): TVSetupView, DPadView, .body, RemoteView, .body, .keyboardSheet, .stateLabel, UIKit

### Community 38 - "Remote key commands"
Cohesion: 0.11
Nodes (18): RemoteKey, back, delete, down, enter, home, left, mute (+10 more)

### Community 39 - "Application engine composition"
Cohesion: 0.18
Nodes (5): TVCompanionApplication, AirPlayCapabilities, AutoStartSettings, ReceiverEngineProvider, LabApplication

### Community 40 - "Receiver event states"
Cohesion: 0.12
Nodes (17): State, APP_VIDEO_ENDED, APP_VIDEO_FAILED, APP_VIDEO_REQUESTED, AUDIO_PLAYING, AUDIO_STOPPED, AUDIO_UNAVAILABLE, CONNECTED (+9 more)

### Community 41 - "Voice capture lifecycle"
Cohesion: 0.22
Nodes (5): MicrophoneSamples, VoiceCapture, .overflowed, VoiceOverflowFlag, .marked

### Community 42 - "Pull request descriptions"
Cohesion: 0.19
Nodes (10): area(), code(), count(), generate(), mergeBody(), run(), assert, fixture() (+2 more)

### Community 45 - "Native receiver signals"
Cohesion: 0.13
Nodes (15): Signal, APP_VIDEO, APP_VIDEO_FAILED, AUDIO, AUDIO_STOPPED, AUDIO_UNAVAILABLE, CONNECTED, DISCONNECTED (+7 more)

### Community 46 - "iOS remote redesign"
Cohesion: 0.14
Nodes (15): Adaptive SwiftUI remote, Bounded ordered key queue, Capability gated Power, Foreground automatic reconnect, iOS remote redesign, Microphone route cleanup, Remote v2 voice, Stable TV profile UUID (+7 more)

### Community 47 - "Remote view model"
Cohesion: 0.19
Nodes (4): .header, RemoteViewModel, .canHoldVoice, .connected

### Community 49 - "AirPlay presentation states"
Cohesion: 0.14
Nodes (13): AirPlayPresentation, Status, AUDIO, BINDING, CONNECTED, FAILED, IDLE, LISTENING (+5 more)

### Community 51 - "Notification receiver service"
Cohesion: 0.19
Nodes (3): pairingPin(), ReceiverService, Bounded resource lifetime

### Community 55 - "Absolute deadline tests"
Cohesion: 0.29
Nodes (4): DeadlineInputTest, ByteArrayInputStream, InputStream, TestSocket

### Community 56 - "AirPlay audio architecture"
Cohesion: 0.21
Nodes (13): AAC MediaCodec decoding, AAudio PCM16 output, ALAC reference decoder, ALAC target byte order correction, Audio and background availability, Auto open incoming video, Background audio, Background listening (+5 more)

### Community 57 - "AirPlay wire phases"
Cohesion: 0.18
Nodes (7): AirPlayPhase, FAILED, READY, STARTING, STOPPED, STOPPING, AirPlayWire

### Community 60 - "Hardware acceptance limits"
Cohesion: 0.20
Nodes (11): Home setup acceptance limits, Phase 3 companion, Remote v2 discovery pairing, Separate notification credential, Urgent alert2 overlay, Broader compatibility unverified, Phase 1 lamp notification acceptance, Phase 2 Spotify ALAC acceptance (+3 more)

### Community 66 - "Receiver integration boundaries"
Cohesion: 0.22
Nodes (10): Application engine composition, Bounded AIDL commands, Foreground only historical checkpoint, Private service playback host, Shared optional lab, Single APK video integration, Foreground development shell, Optional AirPlay lab (+2 more)

### Community 69 - "Sender session replacement"
Cohesion: 0.22
Nodes (9): Bounded video URL validation, Control protocol v5, iPad YouTube compatibility pending, Negotiating socket cleanup, Ordered sender takeover, Platform MediaPlayer HLS, Protected content feedback, Sender replacement and app video (+1 more)

### Community 70 - "Remote connection states"
Cohesion: 0.25
Nodes (8): RemoteConnectionState, connected, connecting, error, needsPairing, notConfigured, offline, reconnecting

### Community 71 - "Remote voice states"
Cohesion: 0.25
Nodes (8): RemoteVoiceState, ending, error, idle, listening, permissionDenied, starting, unavailable

### Community 74 - "Native media delivery"
Cohesion: 0.38
Nodes (4): audio(), feedVideo(), monotonicNs(), video()

### Community 75 - "iOS icon generation"
Cohesion: 0.29
Nodes (4): CoreGraphics, ImageIO, color(), UniformTypeIdentifiers

### Community 76 - "Voice control gestures"
Cohesion: 0.38
Nodes (4): VoiceControl, .body, .enabled, .holdGesture

### Community 77 - "Android dashboard design"
Cohesion: 0.33
Nodes (6): Android TV UI refresh, CleanYourTV static launcher card, Explicit lamp readback, Notification category settings, Stable directional focus, Two card dashboard

### Community 80 - "TV launcher banner"
Cohesion: 0.67
Nodes (3): TVCompanion TV launcher banner, Mint TV mark with gold circle, TVCompanion wordmark

## Knowledge Gaps
- **272 isolated node(s):** `Power`, `WhiteMode`, `PAUSED`, `STARTING`, `LISTENING` (+267 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 663 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **43 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `TVCompanion local-first hub` connect `Project contracts and compliance` to `Android dashboard lifecycle`, `Notification receiver service`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **Are the 26 inferred relationships involving `ReceiverSession` (e.g. with `AirPlaySnapshot` and `.acceptedIsNotReadyAndRepeatedStartDoesNotAllocateAnotherEngine()`) actually correct?**
  _`ReceiverSession` has 26 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Power`, `WhiteMode`, `PAUSED` to the rest of the system?**
  _272 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Android dashboard lifecycle` be split into smaller, more focused modules?**
  _Cohesion score 0.05299608551641072 - nodes in this community are weakly interconnected._
- **Why does `RemoteViewModel` connect `Remote view model` to `Remote identity encoding`, `iOS credential keychain`, `iOS remote interface`, `Remote connection states`, `Remote voice states`, `Voice capture lifecycle`, `Voice control gestures`, `iOS remote redesign`, `iOS TV profile selection`, `iOS application composition`, `Google TV pairing`, `iOS pairing interface`?**
  _High betweenness centrality (0.107) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `TVProfile` (e.g. with `.body` and `.init()`) actually correct?**
  _`TVProfile` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Should `Remote identity encoding` be split into smaller, more focused modules?**
  _Cohesion score 0.0698584763625414 - nodes in this community are weakly interconnected._
## Extraction limitations

- AST extraction reported syntax errors in 11 files and no symbols in 3 additional code files; code coverage may be partial.
- Graph health: 200 dangling-endpoint edges, 76 directed and 100 undirected same-endpoint collapsed edges. These counts overlap and must not be added together.
- Both SVGs were read fully as source; visual rendering was unavailable. The PNG was visually inspected.
- Semantic agent token usage was not exposed by the tools. Recorded zeros are placeholders, not a claim of zero model usage; actual token cost is unavailable.
- Graph outputs are versioned in `graphify-out/`; local extraction caches and machine-specific interpreter/root paths are ignored. Team workflows: https://docs.graphify.com/guides/team-workflows
