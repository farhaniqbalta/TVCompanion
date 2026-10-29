# Source provenance

The initial implementation adapts project-owned source from the sibling Poncol Light and TVNotify projects at the time of this workspace integration:

- Poncol Light: Tuya 3.5 socket/framing/crypto client, encrypted provisioning storage, and eight protocol regression tests. Its original Activity was replaced with a UI-independent controller and a separate TV screen.
- TVNotify: event validation, transient queue, bounded HTTP listener, receiver lifecycle, notification renderer, settings presentation, and regression tests. TVCompanion uses a new package identity, default port 8766, privacy default, separate packages, and tighter input-buffer/lifecycle handling.
- Tuya test vectors were generated with TinyTuya 1.20.0 in the original proof of concept using public synthetic values. TinyTuya runtime/source is not bundled in the Android app.
- Bundled pictograms and TVCompanion brand geometry/lettering are project-authored assets; no protected brand logos or external font outlines are copied. See `brand/README.md` and the standard-library asset generator in `scripts/`.

No credentials, POC environment files, signing keys, generated binaries from sibling projects, or personal device information are copied. Gradle wrapper tooling and build/runtime/test dependencies retain their upstream licenses. Select a repository license and review dependency notices before publishing a release.

## Phase 2 licensing review

The descriptions above record intended origins, not a completed copyright-ownership audit or permission to relicense every contribution. Before selecting release terms, verify authorship and reuse rights for adapted sibling-project files and any third-party expression. See [license compliance](LICENSE_COMPLIANCE.md) for the pinned UxPlay review, dependency findings, proposed original-code licensing strategy and distribution gates. No UxPlay source or new license is added by that review.

The opt-in [AirPlay Lab](../experiments/airplay-lab/README.md) fetches checksum-pinned UxPlay, libplist and OpenSSL sources into an ignored local directory and applies tracked experiment patches. No upstream implementation is copied into core or unrelated features. This introduces an experimental GPL-covered native dependency graph, not an adopted root license or cleared public release.

The selected receiver implementation and its tracked patches now live in `android/integrations/uxplay-native`; `scripts/prepare-airplay-native.py` retains exact source pins and notices. The optional lab reuses that module. The independently authored receiver models, IPC mapping and runtime host contain no copied UxPlay implementation. This describes source separation, not exemption of a combined distribution from GPL obligations.

The 0.5 audio candidate fetches Apple’s ALAC reference decoder (`macosforge/alac` revision `c38887c5c5e64a4b31108733bd79ca9b2496d987`, Apache-2.0, Apple Inc. copyright) into the same ignored native cache. Only the decoder is packaged; the encoder is host-test-only. Attribution/license text is generated into app notices. Audio runtime/clock/queue and UI/lifetime coordination are independently authored; no ALAC/UxPlay implementation is copied into core.
