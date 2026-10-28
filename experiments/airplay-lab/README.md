# TVCompanion AirPlay Lab

Development-only Android TV receiver experiment. This is an optional standalone feasibility harness, **not a second production application**. The intended production artifact remains one TVCompanion APK. The production app does not depend on this module; default builds and CI do not include it.

## Scope

- Pinned UxPlay networking/session core, internal mDNS, libplist and OpenSSL/libcrypto.
- H.264 video through Android NDK MediaCodec directly to a local Surface in `:airplay`.
- Requested 1280×720 / 30 fps, one experimental video pipeline; actual negotiated behavior must be observed.
- No GStreamer, audio playback, HLS player, HEVC, foreground service, boot receiver, persistent reconnect or dashboard polling. The upstream AirPlay/RAOP negotiation profile is retained for client compatibility; negotiated audio packets are discarded.
- Private `:airplay` Activity owns the experiment lifetime; Back/background/surface loss closes the engine. Native initialization and teardown run off the UI thread.
- The default process only displays the launcher/help/notices. No Binder is needed in this foreground-only harness: controls and media rendering are local to the receiver Activity. This does not implement the future production lifecycle/IPC contract.

The local `ReceiverEngine` seam is Android-specific and is not the proposed pure-domain port. The UxPlay adapter and upstream headers stay here; nothing is copied into core, notifications or smart home. The experiment deliberately tests the smallest video path rather than choosing the final production media pipeline.

The receiver has no user access control in this initial private test. It advertises only while its screen is open. Use trusted LAN and non-sensitive test content; no payload/peer/native log strings are forwarded to application logs. A random app-local receiver identity and pairing key stay in private application storage; backups/transfers are excluded. Do not publish APKs or private data from this experiment as a release.

## Reproduce locally

Install JDK 17, Android SDK/platform/build-tools 34, NDK **27.2.12479018** and SDK CMake **3.22.1**. Set `JAVA_HOME` and `ANDROID_HOME` to your local installations. Host preparation requires Python 3, Perl, make and patch. Initial builds need network access and several GB of disk space. Only `armeabi-v7a` is selected for the tested TV; this is not a Play Store or general-device release configuration.

From the repository root:

```sh
python3 experiments/airplay-lab/prepare_native.py
cd android
./gradlew --no-daemon -PairplayLab=true :experiments:airplay-lab:assembleDebug :experiments:airplay-lab:lintDebug
```

APK: `experiments/airplay-lab/app/build/outputs/apk/debug/airplay-lab-debug.apk`.
Package: `id.poncol.tvcompanion.airplaylab`, separate from the working TVCompanion application. Install only on the explicitly authorized test TV with `adb -s <authorized-endpoint> install -r <apk>`. Never uninstall or change signing identity to force an update. No lamp configuration, notification settings or production application update is performed by the harness.

`prepare_native.py` verifies archive SHA256 values before extraction. It retains original sources, license texts, patched sources, build log and native outputs under ignored `.native/`. Preserve that directory if investigating build results. A full clean reproduction can use a fresh source checkout; no private credentials are required. No prebuilt native binaries or upstream source snapshots are committed.

| Dependency | Assessment pin | Archive SHA256 |
| --- | --- | --- |
| UxPlay | `534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc` | `3d77ff2086740e875ba7632703dfc67ca2c46ef4686178542726d539f3e5bde9` |
| libplist | `2.7.0` | `66fa17a29270cb25ceaf189430ea38037c066652c2989b58d7ef6120b1a208d3` |
| OpenSSL | `openssl-3.5.4` | `758b69feed5787dc12d34b0eb29b60d3c9d73d5a64760c62d93a6d26b344d65d` |

These are experiment pins, not declarations of current security suitability or approved production versions. The tracked patches guard HTTP teardown after partial initialization and expose control-request milestones at INFO level. The native adapter emits only fixed stage labels and a coarse sender class; raw upstream messages, headers and payloads are never forwarded. Full upstream debug mode is disabled. Upstream notices remain intact. CMake selects Android library sources and links libcrypto statically; it does not build the desktop executable or GPL GStreamer renderer. GPL PlayFair remains linked, so omitting the desktop renderer does not establish a permissive binary. Generated source/license notices are accessible from the lab home screen. No root LICENSE or original-source license is adopted here. See [compliance review](../../docs/LICENSE_COMPLIANCE.md) before any distribution; generated notices alone are not a complete release source-delivery package.

## Actual TV experiment

1. Open **TVCompanion AirPlay Lab**, then **Open test receiver**. Confirm it says discoverable; a process existing alone is insufficient.
2. On the Mac's Screen Mirroring control, choose **TVCompanion Lab**. Select extended/separate display rather than mirroring.
3. Put different harmless windows on each display. Observe actual picture, text, motion and reconnect. A native “decoder produced video” message means a frame was released to the surface, not visual acceptance or proof of extension.
4. Back out on the TV. Verify advertising/networking stop. Reopen and reconnect. Audio is intentionally unavailable; do not count it as tested.
5. Record results in [Phase 2 experiment results](../../docs/phase-2/TV_RECEIVER_EXPERIMENT.md). Follow the workspace TV/ADB closure procedure after testing. Never scan the LAN or inject unverified remote confirmation keys.
