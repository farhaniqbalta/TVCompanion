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
