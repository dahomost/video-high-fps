// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DahomohaVideoHighFps",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "DahomohaVideoHighFps",
            targets: ["VideoHighFpsPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0")
    ],
    targets: [
        .target(
            name: "VideoHighFpsPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/VideoHighFpsPlugin"),
        .testTarget(
            name: "VideoHighFpsPluginTests",
            dependencies: ["VideoHighFpsPlugin"],
            path: "ios/Tests/VideoHighFpsPluginTests")
    ]
)