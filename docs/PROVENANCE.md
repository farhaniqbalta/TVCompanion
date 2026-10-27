# Source provenance

The initial implementation adapts project-owned source from the sibling Poncol Light and TVNotify projects at the time of this workspace integration:

- Poncol Light: Tuya 3.5 socket/framing/crypto client, encrypted provisioning storage, and eight protocol regression tests. Its original Activity was replaced with a UI-independent controller and a separate TV screen.
- TVNotify: event validation, transient queue, bounded HTTP listener, receiver lifecycle, notification renderer, settings presentation, and regression tests. TVCompanion uses a new package identity, default port 8766, privacy default, separate packages, and tighter input-buffer/lifecycle handling.
- Tuya test vectors were generated with TinyTuya 1.20.0 in the original proof of concept using public synthetic values. TinyTuya runtime/source is not bundled in the Android app.
- Bundled pictograms and TVCompanion brand geometry/lettering are project-authored assets; no protected brand logos or external font outlines are copied. See `brand/README.md` and the standard-library asset generator in `scripts/`.

No credentials, POC environment files, signing keys, generated binaries from sibling projects, or personal device information are copied. Gradle wrapper tooling and build/runtime/test dependencies retain their upstream licenses. Select a repository license and review dependency notices before publishing a release.
