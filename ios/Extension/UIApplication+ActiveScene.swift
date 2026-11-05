import UIKit

extension UIApplication {
  /// The foreground-active window scene, else any connected window scene. Nil when the app has
  /// no UI scene at all (running headless for CarPlay).
  var activeWindowScene: UIWindowScene? {
    let scenes = connectedScenes.compactMap { $0 as? UIWindowScene }
    return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
  }
}

/// The scale to rasterise at for the app's own screen. CarPlay surfaces use
/// `carTraitCollection.displayScale` instead.
// `UITraitCollection.current` reflects the environment being rendered for only on the main actor.
// Not `UIScreen.main.scale`: deprecated, and unavailable on visionOS, which the podspec targets.
@MainActor
var appDisplayScale: CGFloat {
  UITraitCollection.current.displayScale
}
