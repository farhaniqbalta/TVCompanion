// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TVCompanionAudio",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "TVCompanionAudio", targets: ["TVCompanionAudio"])],
    targets: [
        .target(name: "TVCompanionAudio"),
        .testTarget(name: "TVCompanionAudioTests", dependencies: ["TVCompanionAudio"])
    ],
    swiftLanguageModes: [.v5]
)
