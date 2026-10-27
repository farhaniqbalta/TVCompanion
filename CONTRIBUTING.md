# Contributing

Read `docs/ARCHITECTURE.md` before changing code. Keep presentation separate from domain behavior and isolate vendor protocols behind adapters. Add modules for real boundaries, not individual classes. Features must be optional and should not add background work without a concrete need.

Build and run the tasks documented in the README. Add meaningful regression tests for protocol, lifecycle, and state changes; a visual-only change does not need tests that simply repeat its layout. Use synthetic data and report precisely which checks ran. Do not claim hardware validation from unit tests or builds.

Never commit credentials, local device addresses/IDs, signing keys, private configuration, or notification content. Do not attach unredacted logs or configuration dumps. Keep platform builds independent and document changes to external contracts. Do not change or deploy sibling projects as an incidental part of a TVCompanion contribution.

UI changes should preserve D-pad focus, Back navigation, readable text and an idle static screen. Keep polling, retry, queue and allocation behavior bounded. Repository visibility does not grant a license; licensing is still to be selected.
