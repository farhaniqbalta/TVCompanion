# Verification — 2026-10-28

## Current build: 0.2.1-brand-preview

- Gradle debug APK assembly succeeded with JDK 17 and Android SDK 34.
- All 33 functional/regression tests passed: six light-controller tests, five notification-controller tests, two queue tests, nine event-validation tests, three HTTP transport/lifecycle tests, and eight Tuya protocol tests.
- Android app lint, including Android feature/library dependencies, reports zero errors and zero warnings. The custom views are deliberately constructed with state/controller arguments, so XML-inflation constructor checks are narrowly annotated. The pure Kotlin module is compiled and unit-tested separately; Android lint does not analyze it.
- Branding assets are generated from original geometry with the Python standard library: SVG masters, adaptive color/monochrome icons, a 320×180 TV banner, shared headers and a monochrome service icon.
- New direct style/duration selection is validated before preferences change. The UI uses resource strings and plurals.
- Git diff whitespace checks passed. Device captures remain in ignored `artifacts/ui/`, outside tracked source. No credentials or private addresses are included in the research or design notes.

## TV validation

The original foundation was installed and provisioned successfully. Encrypted lamp storage was present and its one-time plaintext import removed. Native lamp status and the dashboard were verified. The user subsequently confirmed that the functionality works.

The UI preview was installed as an update, preserving the existing app identity, signing configuration and private data. Screenshots were inspected for Home, light controls, notification Appearance and Sources. The light layout fits on the current TV without scrolling. The notification overlay renderer/dimensions remain unchanged.

An initial injected-key review assumed the starting page was Home. Captures showed an active light screen, so a center/OK event may have activated its power action. This is not recorded as a deliberate hardware-control test. That sequence was stopped and replaced with checks that inspect the UI and allow only identified navigation labels. The later review activated navigation controls only. Physical remote confirmation of the refreshed focus behavior still belongs to the user; injected events are not a substitute.

The final 0.2.1 branding preview was also installed as an update, and the branded dashboard was captured and visually inspected. The app is left on the new dashboard. App updates may stop a running receiver; open Receiver and start it if needed. No overlay permissions, pairing PINs, source filters, or lamp configuration were deliberately changed by the UI review. ADB closure remains a user-completed step after testing; it has not been verified as closed.

## Resource behavior

No lamp polling, background dashboard animation, wake lock, boot receiver, automatic write retry or recurring UI timer is introduced. The single light worker is closed with the screen, and backgrounding releases screen view trees. Only the explicitly started notification receiver stays active; its listener blocks awaiting work and closes its current connection on pause. HTTP inputs, event acceptance, remembered IDs and event queues remain bounded.

No CPU/RAM benchmark was run, as requested. These are code properties, not measured performance guarantees.

## Remaining work

Narrow/large-text fallbacks are implemented but not hardware-tested across configurations. Fully offline behavior, standby/reboot recovery and protected-player coexistence still require dedicated validation. Automatic iPhone notification capture is not established. Apple remote implementation, Mac support, release onboarding, encrypted notification transport, stronger pairing, additional translations and license selection remain open work.

The Android CI workflow is configured; this record describes local checks rather than a new GitHub Actions run.
