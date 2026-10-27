# Android TV setup

## Build

Install JDK 17 and Android SDK platform/build-tools 34. Set `JAVA_HOME` and `ANDROID_HOME`, or create an ignored `android/local.properties` with `sdk.dir=/path/to/sdk`. Run from `android/`:

```sh
./gradlew :app:assembleDebug :core:domain:test :integrations:tuya-lan:testDebugUnitTest :features:notifications:testDebugUnitTest :app:lintDebug
```

The checked-in Gradle 8.9 wrapper verifies the distribution SHA-256. Initial dependency resolution needs network access; subsequent offline builds work with cached dependencies. CI uses the same tasks. No local credentials are read by the build.

## Optional development lamp provisioning

Only the verified Tuya 3.5 lamp profile is supported: power DPS 20, mode DPS 21, white brightness DPS 22 (10–1000). Other devices and firmware require their own verified adapter/profile. Discovery, color control, scenes and cloud control are not implemented.

Create a private JSON file **outside this repository** with fields `ip`, `id`, `version` and `key`. Use the lamp's actual RFC1918 IPv4 address, device ID, version `3.5`, and 16-byte UTF-8 local key. Never paste its contents into an issue or commit it. `*.private.json` and `provision.json` are ignored as an additional precaution.

After building, authorized developers can explicitly run:

```sh
python3 scripts/provision.py --tv TV_LAN_IPV4 --config /private/path/lamp.private.json
```

This script installs only the new debug application, force-stops it to transfer configuration, passes the payload through ADB stdin, then opens the light screen. The app encrypts credentials with Android Keystore and deletes the one-time plaintext import. Secrets are not passed in command arguments, printed, or embedded in the APK. This does not copy credentials out of another app or depend on sibling repository paths. ADB must already be authorized; the script does not change TV settings or unpair/disconnect other clients.

Opening light controls reads status only. Use the remote to control the lamp after checking setup. No hardware changes are performed by the build or tests. This development provisioning method relies on debuggable `run-as`; a user-facing release onboarding flow remains future work. Debug-signing identities must remain stable for updates; never automatically uninstall to resolve signature conflicts.

## Notification setup

Open Notifications from Home. Preview cards locally before granting overlays. Grant display-over-other-apps permission and explicitly start the receiver. It can be paused from settings or its ongoing notification. After reboot or force stop, open the app and start manually.

The screen shows local addresses, default port 8766, and the per-install PIN. Configure a sender using [notification v1](../contracts/notifications-v1.md). Changing the PIN or port pauses the receiver; update senders before starting it again. Privacy mode is the initial default. No message history is stored.

HTTP currently has no transport encryption and the six-digit PIN is prototype-level pairing. Use trusted LAN and non-sensitive demo content; do not expose the receiver to the internet. TLS/secure pairing and source feasibility are release work, not completed features.

## Status and limits

The new app has not been deployed by this foundation work. Real D-pad behavior, bulb response, offline operation and overlays across players still need device validation. Build/test success does not establish these behaviors. No CPU/RAM benchmarking is requested.
