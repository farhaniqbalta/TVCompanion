# Direct Google TV receiver experiment

> Historical harness evidence. The validated engine has since moved into the [single-APK integration](SINGLE_APK_INTEGRATION.md); the optional lab now shares that implementation. Results below describe the original feasibility run.

Date: 2026-10-29. Branch: `plan/phase-2-airplay`; local, uncommitted experiment. The user approved testing directly on the TV with a development APK, replacing the earlier requirement to first use another desktop receiver host.

## Scope and architecture

The opt-in [AirPlay Lab](../../experiments/airplay-lab/README.md) tests the real Google TV native path. Production remains one TVCompanion APK; the harness is not a separately distributed production receiver. Existing app source/features are unchanged. Android settings adds only a property-gated standalone experiment module; normal builds have no receiver dependency.

The foreground-only harness uses a private playback Activity in `:airplay`, an Android-local engine seam, UxPlay networking, internal mDNS, libplist and OpenSSL. It tests video via Android NDK MediaCodec rather than requiring a complete GStreamer port first. No media crosses IPC; no control Binder is introduced because no cross-process controller is needed for this experiment. The future neutral production runtime/IPC plan remains a later step, not implemented by this harness.

Video-only, H.264, requested 720p/30 fps. No audio, HEVC, HLS, unattended discoverability, background service or production pairing UI. The experiment has no access control and runs only while its test screen is open on the trusted LAN. It is not release-ready.

## Build evidence

- Target observed: Google TV Android 14 / API 34, app ABI `armeabi-v7a` (32-bit ARM).
- NDK 27.2.12479018, CMake 3.22.1, existing AGP 8.5.2 / JDK 17 / SDK 34.
- Native sources and archive checksums pinned by the preparation script; tracked upstream patches handle cleanup after partial initialization and allow lightweight control-stage diagnostics. Sources/notices/outputs stay in ignored `.native/`.
- Native receiver compiled and debug APK built. APK signature verified. The only packaged native entry is `lib/armeabi-v7a/libairplay_lab.so`; dependency license/source notices are generated into assets.
- Lab lint: zero errors, two warnings (TV-sized vector banner, absent ChromeOS ABI). AGP additionally warns that this intentionally 32-bit-only experiment does not satisfy general Play Store 64-bit requirements.
- Existing production app assembly, domain/Tuya/notification tests and app lint passed after adding the opt-in configuration. These are software regression checks, not a new user verification of physical lamp/overlay behavior.

## Device and sender evidence

Installation, runtime startup, discovery, stop/restart cleanup and Mac extended-desktop observations are recorded below as performed. A compiled APK is not proof of AirPlay compatibility.

- Installation/startup: installed alongside the existing app. Initial startup exposed a return-code bug (upstream HTTP start succeeds with `1`, not `0`); corrected and updated successfully. Production package version, code path and last-update time were verified unchanged.
- Dedicated process/native load: process maps verified the receiver ELF mapped from the APK in `:airplay`, absent from the default process. Android reported active `OMX.MS.AVC.Decoder`.
- Mac discovery: this Mac observed the TVCompanion Lab AirPlay advertisement.
- Stop/restart cleanup: pending.
- Sender results: user confirmed discovery and iPhone video AirPlay works. The native decoder produced output buffers and released video to the TV surface. An initial Mac attempt remained loading, and subsequent user attempts failed on both devices. Mac logs included pairing-size failures; no receiver crash or busy rejection was observed. After restoring upstream's AirPlay/RAOP discovery/negotiation profile and disabling full debug mode, the trace identified `sender_kind=MAC`, completed video setup and produced decoder output. The change restores the reference profile; it does not isolate which removed advertisement field/service caused the incompatibility. The user subsequently confirmed: the TV extends this Mac's screen. **True Mac extended desktop is user-accepted on this TV for this video-only experiment.** No screenshot of the user's desktop was captured.
- iPhone: user-confirmed video in an earlier build; revalidation of the final upstream-profile build is not independently recorded.
- Audio: outside this experiment.
- TV/ADB closure: pending during the active test.

## Accepted outcome and remaining limits

True Mac extended desktop on the Google TV is user-confirmed on 2026-10-29. This passes the initial video feasibility gate; it does not establish audio, broad-device compatibility, production background lifecycle, pairing UX, long-session stability, measured latency or licensing clearance. No CPU/RAM benchmark was run.

The accepted session was left active at the user's confirmation. Stop/restart cleanup was not deliberately exercised during that session. ADB closure remains pending until device testing is finished; no debugging-off/revocation/reboot/closed-port verification is claimed. The working production app was not updated, force-stopped or reprovisioned. Nothing was committed or pushed.

Accepted build SHA256: `4ff56fcfa287e041151ca485a3a880be219a32955f0446919e7bb279724bdea5`.
