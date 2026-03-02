// swift-tools-version: 6.0
import PackageDescription

let package = Package(
  name: "AudioBrowser",
  platforms: [.iOS(.v15), .macOS(.v13)],
  targets: [
    .target(
      name: "AudioBrowserTestable",
      path: "ios",
      sources: [
        "Browser/SimpleRouter.swift",
      ]
    ),
    .testTarget(
      name: "AudioBrowserTests",
      dependencies: ["AudioBrowserTestable"],
      path: "ios/Tests"
    ),
  ]
)
