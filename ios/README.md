# TVCompanion for iPhone and iPad

This native SwiftUI client pairs with a Google TV using its Remote v2 service and sends `alert` or `alert2` messages to the TVCompanion Android notification receiver. Mac support remains a later evaluation. It has no UxPlay, Tuya, Android command-server or third-party package dependency.

Open `TVCompanion.xcodeproj` in Xcode 16 or newer, select the `TVCompanion` scheme and an iPhone/iPad running iOS 17 or newer, then select your own development signing team for a device build. For a local simulator build:

```sh
xcodebuild -project ios/TVCompanion.xcodeproj -scheme TVCompanion -configuration Debug -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
swift test --package-path ios/TVCompanionKit
```

The app's **Choose your TV** sheet can find the standard `_androidtvremote2._tcp.` Bonjour service, or accept a manually entered TV address. Pairing shows a six-character hexadecimal code supplied by the Google TV remote service; enter that code on the iPhone/iPad. Remote pairing and the TVCompanion message PIN are separate credentials, both held in the device Keychain. Remote control connects on the TV's Remote v2 service; the notification sender uses the TVCompanion receiver's port (default 8766). Enable notification reception and overlay permission in the Android app before sending. Google TV Remote v2 support and pairing behavior still need a physical device test.

Remote controls include D-pad/Select, Home, Back, volume/mute and basic playback. Each press sends a short key event. Closing/backgrounding the app disconnects the remote; connection is user-initiated. Sending a message creates one stable request ID, and an uncertain delivery can be manually retried with that ID. A `202 queued` receipt confirms acceptance only. The sender reports an older TV that rejects `alert2` rather than silently changing it to `alert`.

The notification API currently uses PIN-authenticated **unencrypted LAN HTTP**. Send non-sensitive messages on a trusted local network. Local network access and Bonjour browsing are declared in `Info.plist`; no automatic capture of other apps' notifications is provided. The pure `TVCompanionKit` package owns wire formats and request/response validation. The app's infrastructure owns TLS, pairing identity, discovery, Keychain and HTTP; SwiftUI views use dedicated view models. Remote TLS pins the peer key learned during pairing. This implementation is based on protocol behavior observed in [androidtvremote2 revision e664a8e](https://github.com/tronikos/androidtvremote2/tree/e664a8e1f14ee458b14c646bfb3ea46f10367051) (Apache 2.0); its source was not copied into this project. On-device pairing, certificate behavior, key mapping and remote delivery remain to be verified before release.
