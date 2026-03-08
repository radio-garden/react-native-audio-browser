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
        "Browser/BrowserPathHelper.swift",
        "Browser/SimpleRouter.swift",
        "Player/QueueManager.swift",
        "Player/ShuffleOrder.swift",
        "Player/LoadSeekCoordinator.swift",
        "Player/SeekCompletionHandler.swift",
        "Player/MediaLoader.swift",
        "Player/MediaLoaderDelegate.swift",
        "Model/TrackPlayerError.swift",
        "Model/NitroTypeStubs.swift",
        "TrackSelector.swift",
        "CarPlay/CarPlayArtworkResolver.swift",
        "Extension/ResolvedTrack+Copying.swift",
      ]
    ),
    .testTarget(
      name: "AudioBrowserTests",
      dependencies: ["AudioBrowserTestable"],
      path: "ios/Tests"
    ),
  ]
)
