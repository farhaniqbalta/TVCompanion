# Apple companion workspace

Intended targets: **iPhone and iPad**. **Mac support** is planned for evaluation, not implemented. The directory is named `ios/` for continuity with the agreed repository layout.

This is a documented workspace placeholder, not an Xcode app or working remote. Android TV Remote v2 discovery, pairing, credential storage, reconnect, and command transport need a dedicated implementation and compatibility validation. No fake connected state or simulated remote is shipped.

The planned dependency direction is SwiftUI presentation -> application use cases -> remote domain/ports <- platform adapters. Keep pairing secrets in Keychain, let the Apple app connect to the TV's supported remote service directly where feasible, and avoid requiring the TVCompanion APK just for remote commands.

Apple builds and releases will be independent of Android. Add an Apple CI job when an actual project and tests exist; Android-only work must not require Xcode.
