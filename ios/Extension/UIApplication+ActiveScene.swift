import UIKit

extension UIApplication {
  /// The scene showing the app's own UI: the foreground-active one, else any
  /// connected window scene. Nil when the app has no UI scene at all (running
  /// headless for CarPlay), which callers must handle rather than assume.
  ///
  /// Replaces the deprecated `UIScreen.main` / `keyWindow` idioms — and exists
  /// once because the walk was previously written out at each use, so the two
  /// copies had already drifted apart.
  var activeWindowScene: UIWindowScene? {
    let scenes = connectedScenes.compactMap { $0 as? UIWindowScene }
    return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
  }
}

/// The scale to rasterise at for the app's own screen.
///
/// `UITraitCollection.current` rather than the deprecated `UIScreen.main.scale`
/// (also unavailable on visionOS, which the podspec targets): on the main actor
/// it already reflects the environment being rendered for. CarPlay surfaces do
/// NOT use this — they rasterise at `carTraitCollection.displayScale`, the car
/// screen's own scale.
@MainActor
var appDisplayScale: CGFloat {
  UITraitCollection.current.displayScale
}
