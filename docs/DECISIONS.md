# Decisions

1. **One repository, independent applications.** Android TV lives in `android/`; the Apple workspace is `ios/`. Builds, signing and releases stay independent. CleanYourTV stays external.
2. **New application identity.** `id.poncol.tvcompanion` prevents accidental updates to existing standalone apps. No automatic private-data migration or uninstallation.
3. **Reuse behavior; replace light presentation.** Preserve tested Tuya framing, authentication, validation and readback. Extract state orchestration from the legacy Activity. Keep notification overlay presentation while separating its infrastructure.
4. **Native lightweight UI foundation.** Use shared Android View primitives now. No UI framework imports in domain/application code. Later visual changes do not require rewriting device control.
5. **Opt-in background work.** Only notifications need a foreground service. No lamp polling, periodic dashboard refresh, boot receiver, or automatic write retry.
6. **Notification migration port.** Default to 8766 instead of TVNotify's 8765. Keep payload aliases, validation, privacy options, and bounded processing. The health identity changes to TVCompanion. Avoid running both receivers in ordinary use.
7. **Honest platform status.** Apple remote is planned; no placeholder connection claims. Mac support requires evaluation. No automatic iPhone notification capture is promised.
8. **Public repository hygiene.** Synthetic fixtures only, no signing secrets or private setup dumps, and no telemetry. A release license remains undecided.
9. **Verification scope.** Build, lint, and meaningful regression checks; no device deployment or benchmarking as part of the foundation.

10. **TV host and Apple clients.** Google TV is the primary orchestration host, while Apple devices are clients. Independent builds do not make them peer hubs. Protocol-specific direct connections remain possible where verified.
11. **Standalone media provider — superseded by decision 13 below.** A future AirPlay receiver will be a separate APK/process, independently usable. It owns discovery, decode and rendering. TVCompanion may control/status it via a future versioned Android IPC adapter; no media frames/audio pass through that adapter. True Mac extended desktop remains a Phase 2 feasibility gate.
12. **Targeted Phase 1.** Preserve module names, package/signing identity, private settings, notification v1 and the current UI. Correct proven lifecycle/I/O weaknesses and document future client-command security before exposing any new endpoint. Actions repair and benchmarks remain deferred.

## Phase 2 packaging correction — 2026-10-28

13. **One production TV APK; dedicated AirPlay process.** Supersedes decision 11. TVCompanion is the Google TV integration host. Bundle the future UxPlay integration in TVCompanion's APK, with a private `:airplay` Android process owning its service, JNI/native core, sessions, decoder and audio/video rendering. Main-process UI/orchestration uses control/status/lifecycle Binder/AIDL only; Mac media connects directly to the receiver stack. No frames/audio cross IPC. Android IPC types remain outside the pure Kotlin domain. Notifications and smart home have no dependency on UxPlay. No universal plugin framework, registry or event bus is justified.

Independent native development/testing and an optional standalone development harness are permitted; neither implies a separately distributed production APK. The service owns explicit start/stop/cleanup, native loading stays in the receiver process, and idle dashboard display does not start it. Process separation is not `isolatedProcess=true`, a resource guarantee or a license exemption.

True extended desktop from the selected Mac, Android NDK portability, discovery/pairing, target-TV decode/rendering, stability/latency and distribution licensing remain gates. This decision changes the target architecture; it does not assert feasibility or add runtime code. Preserve Phase 1 historical records with superseding notices. See the [migration and feasibility plan](phase-2/PLAN.md).
