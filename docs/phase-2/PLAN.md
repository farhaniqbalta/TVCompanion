# Phase 2 — AirPlay feasibility and single-APK integration

Status: initial video feasibility passed; foreground-only single-APK integration implemented and user-accepted, updated 2026-10-29. Working branch: `plan/phase-2-airplay`. Baseline: `b876c0d` (merged Phase 1 and CI/PR automation). [Decision 13](../DECISIONS.md#phase-2-packaging-correction--2026-10-28) supersedes the separate-production-receiver APK design.

## Inspected state and impact

The baseline has six Android modules: app, domain, design system, notifications, smart home and Tuya LAN. There is no AirPlay module, service, AIDL, JNI, native build configuration or native receiver code to discard. The working tree was clean at assessment. Phase 1's reported 59 passing tests, build/lint and user lamp/notification acceptance are historical evidence, not checks rerun for this documentation change.

Separate-APK assumptions existed in README, architecture, decision 11, Apple workspace README, receiver contract and Phase 1 documents. Current documents now use one production APK; Phase 1 documents retain their original text with superseding notices. The parent `TVCOMPANION_ARCHITECTURE_HANDOFF.md` remains an external historical input, not current packaging authority. No sibling project is changed.

Reuse the existing UI/domain/infrastructure separation, direct media path, control-only IPC principles and bounded lifecycle rules. Replace external package discovery/signing/missing-provider assumptions with private same-app binding, initialization failure and receiver process death. Preserve app identity, signing, private settings, notification v1, Tuya behavior and existing feature dependencies.

## Target boundaries

```text
android/
  app/                         composition/navigation; one production APK
  core/domain/                 pure application models/ports when needed
  features/airplay/            main-process presentation/controller/IPC adapter
  ipc/airplay-contract/        Android-only AIDL and small control/status types
  integrations/airplay-runtime/ :airplay service, playback host, local engine API
  integrations/uxplay-native/  selected engine, JNI/native code and dependencies
```

These new modules are proposed boundaries, not generated scaffolding. The [receiver boundary review](RECEIVER_BOUNDARY_REVIEW.md) refines the earlier combined native module into a reusable Android runtime and a replaceable engine. The app packages receiver components without eagerly loading native libraries. The AirPlay feature depends on domain/design-system and the IPC contract; the runtime depends on the IPC contract, and the engine implements the runtime-local API without depending on main-process presentation. Build-time app composition selects the engine; runtime plugin loading is out of scope. Existing notifications/smart-home/Tuya modules do not depend on the native integration. No registry, plugin loader or event bus is needed.

Mac -> AirPlay network stack -> native media pipeline -> local decoder/audio/surface, all receiver work in `:airplay`. Main-process Binder/AIDL carries commands, acknowledgements, status and lifecycle events only. The service and proposed private playback Activity use the same process; surfaces stay local. Audit Application/providers/static initializers for accidental per-process startup. `android:process=":airplay"` is distinct from permission-restricted `isolatedProcess=true`.

## Reversible work sequence and gates

| Step | Work and evidence required | Exit / rollback |
| --- | --- | --- |
| P2.0 — Direction correction | Reconcile current docs and contract; retain historical Phase 1 records. No Gradle, manifest or runtime changes. | Documentation review; revert this documentation diff only. |
| P2.1 — Pin source and assess distribution | Pin a UxPlay revision; inventory native/GStreamer/crypto/discovery dependencies, licenses, source/notice obligations, Android build assumptions and target ABI requirements. Select a compatible toolchain deliberately. | Record a dependency/build matrix and unresolved blockers before bundling. No claim that process separation resolves licensing. |
| P2.2 — Prove sender behavior | Use this Mac as sender and the authorized TV running the development-only receiver harness (user-approved direct-TV route). Confirm an independently extended desktop with distinct windows/content, display settings and reconnect. Record OS/upstream revisions and actual results without private identifiers. | Mirroring alone fails the gate. The TV harness combines initial portability and sender gates; stop broader production integration if extension is unavailable. A receiver on the same Mac does not prove the intended two-device path. |
| P2.3 — Validate Android media path | Compile the smallest native experiment; validate discovery, connection, hardware decode, local surface rendering, audio and surface recreation on the intended Google TV. A development-only harness is optional. | Record reproducible build and device evidence; retain experiment separately if blocked. No production second APK or placeholder “working” UI. |
| P2.4 — Establish receiver ownership | Package the validated library in the single APK, private `:airplay` service/playback components, explicit native init/stop/cleanup, and a verified foreground/launch policy. | Receiver failures do not load native code in main or change lamp/notification behavior. Keep focused commits reversible together. |
| P2.5 — Control and presentation | Add the minimal focused application port, AIDL adapter and TV UI with scoped observation and explicit start/stop. | Bounded calls, death handling, stale-callback suppression and honest state; no media IPC. Revert UI/adapter independently of native experiment. |
| P2.6 — Acceptance | Run relevant build/lint/regression tests, inspect merged manifest/APK/native dependencies and test the authorized TV. | One install/update artifact; real extension/discovery/pairing/reconnect/cleanup confirmed; Phase 1 behavior preserved. Record failures and limits rather than assuming acceptance. |

P2.0 and the P2.1 licensing/boundary assessment are documented. The user subsequently approved a direct-TV experiment; see [harness results](TV_RECEIVER_EXPERIMENT.md). The exact reviewed upstream revision, dependency findings and release obligations are recorded in [license compliance](../LICENSE_COMPLIANCE.md); Android dependency versions/plugins and full ownership review remain open. Later steps remain gated by evidence; this plan alone is not evidence of receiver operation; actual deployment/results belong in the experiment record. Do not download/port a full native stack merely to populate the folder tree.

## Lifecycle and resource decisions to resolve

- Define user-controlled discoverable-idle lifetime versus active playback. Do not assume an idle receiver can use active-playback foreground-service rules indefinitely. Validate service type/permissions and platform start restrictions against the target Android version.
- Define user-initiated playback entry and behavior when a sender connects while another app is visible. A service alone does not provide a rendering window or permission to launch UI from the background.
- Handle audio focus, Back, backgrounding, surface destruction/recreation, sender disconnect and Android process reclamation. A binding is not proof native initialization succeeded.
- Bound sessions, input buffers and pending control work. Release discovery/networking/decoder/audio/surface resources on stop. No unconditional wake lock, boot startup, permanent reconnect loop or dashboard polling.
- Native crashes have a process boundary but still consume shared TV resources. Keep CPU/RAM considerations in implementation choices; no benchmark suite is introduced. Functional stability and observable playback delay still need device validation.
- Keep pairing material out of broad status/logs. Do not expose a service or reuse notification PIN authentication to make receiver control easier.

## Feasibility research and limits

Upstream UxPlay documents macOS second-display support and a GStreamer media pipeline. This supports investigation; it does not establish compatibility with this Mac or an Android port. Its current development branch includes experimental discovery changes, so select and pin a revision rather than assuming current master is production-ready. [UxPlay source and README](https://github.com/FDH2/UxPlay).

GStreamer's Android sink requires an application-provided surface and lifecycle updates. Validate this path before selecting a replacement renderer; avoid simultaneously rewriting networking and the whole media pipeline without evidence. [Android video tutorial](https://gstreamer.freedesktop.org/documentation/tutorials/android/video.html). Android native builds use an NDK toolchain, not a desktop executable copied into an APK. [NDK CMake guidance](https://developer.android.com/ndk/guides/cmake).

UxPlay identifies GPLv3 licensing and inherited components. TVCompanion has not selected a release license. Inventory exact dependencies and review compatible source, notices and distribution requirements before shipping the combined APK; runtime process separation is not an exemption. Upstream also documents FairPlay-related concerns that remain part of the distribution review. [Upstream licensing and provenance](https://github.com/FDH2/UxPlay#readme).

Android supports per-component process assignment; separate-process startup and process-local state need deliberate ownership. [Processes and threads](https://developer.android.com/guide/components/processes-and-threads), [service manifest attributes](https://developer.android.com/guide/topics/manifest/service-element). Foreground-service types and restrictions require a validated lifecycle, not a copied declaration from the notification service. [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

## Planned validation

Documentation step: check relative links, whitespace, residual separate-APK claims and changed-file scope. Do not rerun app tests merely for prose changes.

Implementation: run the repository's existing build/test/lint tasks plus focused AirPlay lifecycle/IPC tests justified by actual behavior. Cover native initialization failure, process death, repeated start/stop, stop during startup, callback cleanup, late replies, surface loss/recreation and idle shutdown. Inspect packaged components, ABIs and process assignments; verify the main process does not initialize UxPlay and receiver startup does not initialize unrelated features.

Real-device acceptance: preserve signing/settings with an update, never uninstall to repair signatures. Verify notification reception/overlays and user-observed lamp behavior while AirPlay starts/stops or fails. Use synthetic notifications and explicit lamp actions, no blind remote input. Record true extended desktop, discovery/pairing, audio/video, reconnect and visible latency/stability separately from compilation success. Keep device identifiers/credentials private and follow the workspace's TV/ADB procedure when deployment is authorized.

## Direct-TV video gate result — 2026-10-29

The user confirmed true extended desktop from this Mac to the Google TV using the optional [video receiver harness](TV_RECEIVER_EXPERIMENT.md). This establishes initial native video feasibility on that target; it does not clear production service/IPC integration, audio, long-session stability or distribution licensing. Preserve the experiment and use its findings to implement the smallest neutral engine/runtime seam when that work is requested.

## Integrated checkpoint and next work

The [single-APK integration](SINGLE_APK_INTEGRATION.md) is implemented as `0.4.0-airplay-preview`, installed with settings preserved and user-accepted. Audio is next, followed by an explicitly enabled idle/background availability policy owned by the service independently of the playback UI. The user requested no additional testing at this checkpoint; previous test/build evidence is retained without claiming new device checks.

## Audio and kept-availability candidate

Both requested next steps are implemented in the [0.5 candidate](AUDIO_AND_BACKGROUND.md): local audio decoding/output/focus and opt-in service-owned background discovery with notification controls. Software checks pass; hardware behavior awaits user review after the requested installation. No automatic boot/restart or background UI takeover is introduced.
