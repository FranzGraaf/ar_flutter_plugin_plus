// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "ar_flutter_plugin_plus",
    platforms: [
        .iOS("15.0")
    ],
    products: [
        .library(name: "ar-flutter-plugin-plus", targets: ["ar_flutter_plugin_plus"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        .package(url: "https://github.com/google-ar/arcore-ios-sdk.git", from: "1.51.0"),
        .package(url: "https://github.com/magicien/GLTFSceneKit.git", from: "0.4.0"),
        .package(url: "https://github.com/Kitura/Swift-JWT.git", from: "4.0.0"),
    ],
    targets: [
        .target(
            name: "ar_flutter_plugin_plus",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "ARCoreCloudAnchors", package: "arcore-ios-sdk"),
                .product(name: "ARCoreGeospatial", package: "arcore-ios-sdk"),
                .product(name: "GLTFSceneKit", package: "GLTFSceneKit"),
                .product(name: "SwiftJWT", package: "Swift-JWT"),
            ]
        )
    ]
)
