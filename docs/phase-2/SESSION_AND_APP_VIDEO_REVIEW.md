# AirPlay sender replacement and in-app video review

Reviewed 2026-10-30 on `investigate/airplay-session-video-flow`, created from main `7397789`. The requested override means **a second sender replaces the connected sender**. YouTube/Crunchyroll reports refer to the apps’ AirPlay buttons, not Control Center screen mirroring. The first sections record the source/documentation assessment of that baseline. The authorized local implementation and verification follow below; historical descriptions of disabled features refer to the baseline, not the new candidate.

## Sender replacement

The pinned UxPlay source supports both hold-first and replace-current behavior. TVCompanion currently calls `raop_init2(server, 0, ...)`, disabling upstream `nohold`. In `lib/raop.c`, another RAOP connection is then rejected with RTSP/HTTP status 409 while the current one remains active. The adapter's `report_client_request` callback admitting a client does not override this earlier gate. [Pinned upstream source](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/raop.c), [documented nohold behavior](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/README.md).

With `nohold` enabled, upstream marks known old connections for removal and invokes `video_reset(..., RESET_TYPE_NOHOLD)` before the new connection proceeds. This permits the requested takeover, but enabling the flag alone does not establish correctness of TVCompanion's lifecycle:

- The native reset callback currently ignores reset reason, clears decoder/configuration/preroll and ends audio.
- Main-thread receiver status has engine-level stale-callback protection, not an explicit sender-replacement transition. Old disconnect/audio-stop callbacks must not clear the new session or shut down the receiver during handoff.
- The automatic-presentation gate resets on disconnect/new engine. A replacement needs a deliberate reset so the new sender may open playback once, without defeating Back for the existing sender.
- A retained hidden session with background listening off withdraws discovery. A second sender may not find it; accepting takeover is distinct from deciding when to advertise.
- Audio focus, buffered samples and the attached local Surface must transfer through cleanup without mixing old/new audio or restarting the entire engine after the new sender begins.

Recommended small implementation: enable the existing upstream takeover mechanism inside the UxPlay adapter, preserve the reset reason as an implementation-independent session-replacement event where needed, and coordinate handoff in the existing lifecycle owner. Preserve the one-APK/private-process/control-only IPC architecture. No plugin registry or second receiver is needed. Validate Mac→iPad and iPad→Mac, video↔audio, a hidden session, old sender disconnect during handoff, and explicit Stop. These outcomes are untested in this review. The desired product policy is newest accepted sender wins; this is not asserted as a universal behavior of every AirPlay receiver.

## App video versus mirroring

Screen mirroring sends the screen's compressed video/audio directly to the native receiver. The working H.264/ALAC paths do not imply support for an app's separate video-streaming protocol. In-app AirPlay video can ask the receiver to fetch/play a video stream and report playback state rather than sending mirrored screen frames.

### YouTube: a concrete missing implementation

The pinned upstream README explicitly describes audio-only fallback from the YouTube app when its HLS option is disabled, and scopes its HLS support to the YouTube iOS app. The current TVCompanion adapter:

1. Leaves `raop->hls_support` at its default false and never calls `raop_set_plist(server, "hls", 1)`.
2. Clears discovery feature bits 0 and 4 (alongside bit 42), rather than advertising app-video support.
3. Does not provide `on_video_play`, scrub/rate/stop, playback-info or playlist-removal callbacks.
4. Contains a local Surface-based H.264 renderer and native audio sink, but no HLS player.

Upstream consequently ignores the separate video-streaming requests while an audio route can still play. This is a capability gap consistent with the user's YouTube symptom, not evidence that the already working mirror decoder regressed. [Pinned README](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/README.md), [request gate](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/raop.c), [callback contract](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/raop.h).

Do not merely enable HLS/discovery flags: the required callbacks, playback state and player lifecycle must exist first. A focused Android HLS player may live inside the selected implementation in `:airplay`, using the same local display boundary. Its native-to-Java request bridge is local to the receiver process; stream URLs/media must not be added to main-process control IPC. Evaluate the pinned upstream playlist/proxy/control flow before selecting player dependencies. Media3 HLS is a candidate to assess, not an adopted dependency or proven compatibility result. Include source/notices/license review for any new packaged components. No GStreamer desktop renderer is currently packaged.

### Crunchyroll: do not assume the YouTube solution applies

The user's in-app AirPlay attempt encounters an unsupported app-video path in this implementation. The pinned UxPlay documentation does not claim broad service compatibility; its HLS support is scoped to YouTube. It also documents lack of Apple protected-video decryption. Crunchyroll may require a different streaming path, authorization or content protection; **DRM as the cause of this specific attempt remains unverified**. Audio-only symptoms alone do not establish which restriction applies.

Crunchyroll's official device documentation lists Android/Google TV and Chromecast, and its Google TV guide describes its native app and mobile casting. These are supported alternatives for viewing the service; they are not proof that its protected streams work in a third-party AirPlay receiver. [Supported devices](https://help.crunchyroll.com/hc/en-us/articles/24181606177300-What-devices-are-supported-by-Crunchyroll), [Google TV guide](https://help.crunchyroll.com/hc/en-us/articles/22701571540628-Getting-started-with-Google-TV).

Assess a specific Crunchyroll sender/app version and a fresh allowlisted request trace independently. Do not log media URLs, authorization headers, manifests, track metadata or keys. Do not promise protected-video support, infer that all Crunchyroll content has the same restrictions, or propose bypassing content protection.

## Proposed order

1. Implement and verify explicit sender takeover, preserving existing Bardi, notifications, mirroring and Spotify behavior.
2. Improve capability/help messaging so app-video limitations are distinguishable from a mirror connection waiting for frames; avoid claiming DRM was detected without evidence.
3. Run a scoped YouTube HLS feasibility implementation in the current receiver implementation/process, with all playback-control callbacks and clean handoff between mirror/audio/HLS.
4. Assess Crunchyroll separately from the clear-video YouTube gate. Its compatibility is not an acceptance criterion silently implied by adding HLS.

No APK was built/installed and no tests or commit/push were performed during the initial documentation-only assessment. The subsequent implementation below supersedes that local-work status.

## Authorized implementation — 0.7.0 candidate

Version **0.7.0-airplay-app-video-preview**, versionCode **15**. The user authorized all three work items, then explicitly instructed **not to install yet**. Changes remain local on this investigation branch; no TV connection, installation, lamp command or casting session was performed.

### Sender takeover

The UxPlay adapter now enables `nohold`. A small maintained patch (`0003-ordered-sender-takeover.patch`) makes old known connections close on the native request-owner thread before the new connection is admitted as RAOP. That cleanup joins old RTP workers rather than allowing deferred removal to overlap the new media setup. The new unknown connection survives cleanup. Existing asynchronous-removal APIs used elsewhere remain unchanged.

Native handoff suppression prevents old mirror-stop callbacks from reporting a disconnect during this ordered cleanup. A neutral SESSION_REPLACED event keeps the lifecycle owner alive, clears old media state/errors and resets one-shot presentation without creating another native engine. New media callbacks resume normal reporting. As in upstream nohold, a new connection attempt can displace the old sender during negotiation; this is not an authenticated-only admission policy or a claim about every AirPlay receiver's behavior. Existing trusted-network/no-PIN admission remains unchanged.

### HLS playback boundary

The adapter implements all pinned upstream player callbacks: play, seek, rate (pause/resume), stop, playback-info and playlist removal. Video capability flags are enabled only with that callback implementation present. Control protocol **v5** reports an implementation-independent app-video capability and typed protected/unsupported-media issues; URLs, manifests, tokens, frames, audio and surfaces do not cross main-process IPC.

The player is Android's platform **MediaPlayer**, instantiated only for a requested video stream inside `:airplay`. It stays entirely inside `integrations:uxplay-native`; replacing that integration removes it without changing unrelated features. Media3 was considered but is not added for this first feasibility path: the platform already provides asynchronous HLS playback and control APIs without another packaged dependency. Platform media support varies by TV/firmware, so the choice remains subject to actual YouTube testing. [Android supported media formats](https://developer.android.com/media/platform/supported-formats), [MediaPlayer API](https://developer.android.com/reference/android/media/MediaPlayer).

The pinned upstream proxy handles YouTube's playlist/reverse-request flow. The local player accepts only bounded HTTPS URLs or the receiver's exact-port loopback HTTP proxy URL; it rejects file/content schemes, embedded userinfo, fragments, oversized URLs and invalid seek positions. Android cleartext permission is limited to loopback domains; HTTPS retains platform certificate validation. No service-specific extraction, sign-in, credential forwarding or DRM license provisioning is added.

The upstream proxy profile filters to AVC up to 720p at 30 fps, excluding HEVC/VP9/AV1. The player also rejects video dimensions beyond the preview pixel budget. Direct HTTPS streams are not automatically rewritten by that profile, so direct-source frame rate and codec compatibility are not guaranteed. One platform player is active; old callbacks are ignored by player identity. Preparation has a 20-second deadline; seeking has a five-second deadline; both are cancelled on cleanup. Progress is an immutable, bounded estimate sampled at player events and queried by native code, with no repeating UI timer.

HLS takes ownership of the local display only after the mirror decoder is released. On HLS→mirror transition, the native decoder waits for the platform player's release acknowledgment before attaching to the Surface. Native startup caching remains bounded. Reattaching an already rendered HLS stream reparses/prepares at its saved position so the new Surface gets a fresh first-render event; the UI does not infer displayed video from a previous Surface. This may rebuffer on return and needs device validation.

Audio obeys the existing background policy, audio focus, sender gain and ducking. Without a display or allowed background sound the player pauses; with allowed background audio it can continue under platform playback behavior. No custom hidden-frame queue is added; this is not proof that a particular firmware avoids all hidden video decoding. Explicit stop/close releases the player and focus. Mirror/audio stop events during a mode transition do not abandon the new HLS session's focus. The previously accepted ALAC byte-order correction is retained.

### Crunchyroll assessment and failure handling

Crunchyroll is **not declared compatible**. The same bounded path may attempt a service-provided HTTPS video URL or upstream-supported HLS request. The player detects reported DRM information and returns PROTECTED_CONTENT without requesting a license; unsupported format/path and generic network/player failure have separate outcomes. [Android DRM-info callback](https://developer.android.com/reference/android/media/MediaPlayer.OnDrmInfoListener).

Unsupported FairPlay/app-video requests also become a generic unsupported-media status rather than a false claim of confirmed DRM. Both settings and playback explain the limitation, with a supported TV app/receiver as the recovery path. The receiver can accept another stream after failure; problem status remains visible through sender disconnect while the UI/listener remains present. No keys, media URLs, manifests or track metadata are included in application diagnostics. Neither this code nor public documentation establishes which protection/authorization path the user's particular Crunchyroll episode uses. That needs a fresh sender/app-specific trace after a future authorized installation.

### Local verification and remaining gate

The combined APK builds and **111 JVM tests** pass, including existing Bardi, notifications, audio/mirroring suites and new takeover-state, app-video failure, protocol capability/error, request-bound and progress-estimation cases. Native media helper checks pass. The new source patch was reversed/reapplied in a temporary copy with exact source restoration. Lint has zero errors and the existing ChromeOS ABI warning. These checks do not execute Android's player or a physical sender handoff.

Deferred device matrix, only after installation is authorized:

| Flow | Required observation | Current result |
| --- | --- | --- |
| Mac→iPad and iPad→Mac takeover | New sender replaces old; old audio/video/callbacks do not disturb it | Untested |
| Spotify↔mirror and hidden retained takeover | Correct focus, policy and one automatic presentation attempt | Untested |
| YouTube app AirPlay | First picture/audio, pause/resume/seek, ads/next item and Stop | Untested |
| HLS↔mirroring and leave/reopen | Correct display ownership, no stale overlay/audio, bounded recovery | Untested |
| Crunchyroll app AirPlay | Actual request route; playback or accurate unsupported/protected status | Unverified compatibility |
| Bardi and notifications | Existing behavior remains intact on the new APK | Local suites pass; device regression pending |

No installation, benchmark, commit or push is included in this implementation turn. Native-library licensing and corresponding-source obligations remain as recorded in LICENSE_COMPLIANCE.md; no root license is changed.

### Home installation and user acceptance

The user later requested installation of this branch. The versionCode 15 debug APK built successfully and installed as an update over TVCompanion versionCode 14 on the home TV. The installed package reported `0.7.0-airplay-app-video-preview`. The user subsequently reported “it works.” This confirms the tested AirPlay update at the level reported by the user; the exact sender app, media title, and individual YouTube/Crunchyroll scenarios were not separately identified. Protected-media compatibility and broad app-video support remain unverified.

### Follow-up handoff candidate (0.7.2)

After the first home test, the user reported that iPhone YouTube AirPlay played but iPad YouTube did not, and that iPad → iPhone takeover could leave the iPad showing a connected session with a loading/conflict screen. A focused native patch now preserves additional RAOP setup sockets from the same network peer while detecting a different peer through either RAOP or AirPlay control connections. It retains ordered cleanup and does not change media-frame IPC or the player. VersionCode 18 (`0.7.2-airplay-handoff-preview`) built, passed focused AirPlay/runtime/native tests and lint, and installed on the TV. Logs from one session reached `HLS_PREPARED` and `HLS_VIDEO_OUTPUT`; other attempts ended before a video-play callback. The exact iPad YouTube path and full reverse-takeover result remain unverified. The upstream HLS feature remains limited to the YouTube iOS app; Crunchyroll support is not claimed.

Takeover closes receiver connections that have been classified as RAOP, AirPlay, event, or HLS. A socket still classified as unknown during negotiation can survive that cleanup, and the sender's AirPlay UI may take time to reflect a closed connection. This is a plausible cause of the iPad continuing to display “connected,” but has not been isolated as the sole cause. Keep the PR in draft until iPhone → iPad → iPhone and existing mirroring/audio flows are checked on device.

### Negotiating-socket handoff candidate (0.7.3)

In the latest reported iPad → iPhone case on 0.7.2, receiver stages show `SENDER_TAKEOVER`, then new session setup and audio output, followed by teardown and repeated pairing. This proves the initial takeover was admitted; it does not identify which sender issued the later requests. The previous cleanup closed only classified connections, leaving sockets still marked unknown during negotiation. VersionCode 19 (`0.7.3-airplay-handoff-preview`) closes sockets from the displaced network peer, including unknown ones, while retaining concurrent setup sockets from the incoming peer. The patch applies cleanly, the APK builds, focused AirPlay/runtime/native tests and lint pass, and the APK installed on the TV. Reverse takeover, sender UI disconnect and existing mirroring/audio remain pending device verification; this is a candidate fix, not an accepted result.
