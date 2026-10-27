# Foundation verification — 2026-10-28

## Completed locally

- Gradle debug APK assembly succeeded with JDK 17 and Android SDK 34.
- All 32 functional/regression tests passed: six light-controller tests, four notification-controller tests, two transient queue tests, nine event-validation tests, three HTTP transport/lifecycle tests, and eight Tuya protocol tests.
- Android app lint, including its Android feature/library dependencies, completed with zero errors and fifteen warnings: thirteen English string/localization warnings and two constructors for views deliberately created with injected dependencies. The pure Kotlin domain is compiled and unit-tested separately; Android lint does not analyze that JVM module.
- Source XML parsed successfully and provisioning script syntax/help checked. No provisioning or deployment was performed.
- Domain/application sources contain no Android imports.
- Git ignore checks confirmed generated APKs, Gradle caches, local SDK properties and private provisioning filenames are excluded. Public file listing contains no machine-specific home paths or private-key blocks. Synthetic protocol fixtures intentionally contain public dummy keys.
- GitHub Actions Android verification workflow is configured. It has not been run on GitHub by this work.

## Resource behavior reviewed in code

No lamp polling, background dashboard animation, wake lock, boot receiver, automatic write retry or recurring UI status timer. A single light worker is closed with the screen. Dashboard and notification settings view trees are released on backgrounding. Only the explicitly started notification receiver stays active; its listener blocks awaiting work and closes its active connection on pause. HTTP input, accepted event rate, remembered IDs and event queues are bounded.

No CPU/RAM benchmark was run, as requested. These are code properties, not measured performance guarantees.

## Still unverified or planned

The new application has not been installed on a TV. Real D-pad navigation, visible bulb response, fully offline behavior, lifecycle/player coexistence and protected-content overlays remain hardware checks. Prior standalone app results do not establish these behaviors for this package.

Apple remote implementation and Mac support are not complete. Release onboarding, encrypted notification transport, stronger pairing, localization and license selection remain open work. See the README and security notes for current limitations.
