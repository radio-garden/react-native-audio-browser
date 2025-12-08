import Foundation
import NitroModules

/// Flattened browser configuration (matches Kotlin BrowserConfig).
///
/// This is a convenience wrapper around NativeBrowserConfiguration that
/// provides easier access to configuration properties.
struct BrowserConfig {
  /// Base HTTP request configuration
  let request: TransformableRequestConfig?

  /// Media URL transformation configuration
  let media: MediaRequestConfig?

  /// Artwork URL transformation configuration
  let artwork: MediaRequestConfig?

  /// Routes as array with flattened entries
  /// Includes __tabs__, __search__, and __default__ special routes
  let routes: [NativeRouteEntry]?

  /// Behavior: single track playback vs queue expansion
  let singleTrack: Bool

  /// Behavior: show offline error in Android Auto controller
  let androidControllerOfflineError: Bool

  init(
    request: TransformableRequestConfig? = nil,
    media: MediaRequestConfig? = nil,
    artwork: MediaRequestConfig? = nil,
    routes: [NativeRouteEntry]? = nil,
    singleTrack: Bool = false,
    androidControllerOfflineError: Bool = true
  ) {
    self.request = request
    self.media = media
    self.artwork = artwork
    self.routes = routes
    self.singleTrack = singleTrack
    self.androidControllerOfflineError = androidControllerOfflineError
  }

  /// Create from NativeBrowserConfiguration
  init(from config: NativeBrowserConfiguration) {
    self.request = config.request
    self.media = config.media
    self.artwork = config.artwork
    self.routes = config.routes
    self.singleTrack = config.singleTrack ?? false
    self.androidControllerOfflineError = config.androidControllerOfflineError ?? true
  }

  /// Returns true if search functionality is configured (either callback or config).
  var hasSearch: Bool {
    guard let routes = routes else { return false }
    let searchEntry = routes.first { $0.path == BrowserManager.searchRoutePath }
    return searchEntry?.searchCallback != nil || searchEntry?.searchConfig != nil
  }
}

// MARK: - NativeRouteEntry + RouteEntry conformance

extension NativeRouteEntry: RouteEntry {}
