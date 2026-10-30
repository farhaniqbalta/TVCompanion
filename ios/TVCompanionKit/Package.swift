// swift-tools-version: 6.0
import PackageDescription
let package = Package(
    name: "TVCompanionKit", platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "TVCompanionKit", targets: ["TVCompanionKit"])],
    targets: [.target(name: "TVCompanionKit"), .testTarget(name: "TVCompanionKitTests", dependencies: ["TVCompanionKit"])],
    swiftLanguageModes: [.v5]
)
