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

  // MARK: - CarPlay Options

  /// Enable the "Up Next" button on CarPlay Now Playing screen
  let carPlayUpNextButton: Bool

  /// Custom buttons for CarPlay Now Playing screen (e.g., .repeat, .favorite)
  let carPlayNowPlayingButtons: [CarPlayNowPlayingButton]

  /// Callback to customize navigation error display (for i18n)
  /// Used by CarPlay and available via `useFormattedNavigationError()` for app UI.
  let formatNavigationError: ((_ params: FormatNavigationErrorParams) -> Promise<FormattedNavigationError?>)?

  init(
    request: TransformableRequestConfig? = nil,
    media: MediaRequestConfig? = nil,
    artwork: MediaRequestConfig? = nil,
    routes: [NativeRouteEntry]? = nil,
    singleTrack: Bool = false,
    androidControllerOfflineError: Bool = true,
    carPlayUpNextButton: Bool = true,
    carPlayNowPlayingButtons: [CarPlayNowPlayingButton] = [],
    formatNavigationError: ((_ params: FormatNavigationErrorParams) -> Promise<FormattedNavigationError?>)? = nil,
  ) {
    self.request = request
    self.media = media
    self.artwork = artwork
    self.routes = routes
    self.singleTrack = singleTrack
    self.androidControllerOfflineError = androidControllerOfflineError
    self.carPlayUpNextButton = carPlayUpNextButton
    self.carPlayNowPlayingButtons = carPlayNowPlayingButtons
    self.formatNavigationError = formatNavigationError
  }

  /// Create from NativeBrowserConfiguration
  init(from config: NativeBrowserConfiguration) {
    request = config.request
    media = config.media
    artwork = config.artwork
    routes = config.routes
    singleTrack = config.singleTrack ?? false
    androidControllerOfflineError = config.androidControllerOfflineError ?? true
    carPlayUpNextButton = config.carPlayUpNextButton ?? true
    carPlayNowPlayingButtons = config.carPlayNowPlayingButtons ?? []
    formatNavigationError = config.formatNavigationError
  }

  /// Returns true if search functionality is configured (either callback or config).
  var hasSearch: Bool {
    guard let routes else { return false }
    let searchEntry = routes.first { $0.path == BrowserManager.searchRoutePath }
    return searchEntry?.searchCallback != nil || searchEntry?.searchConfig != nil
  }
}

// MARK: - NativeRouteEntry + RouteEntry conformance

extension NativeRouteEntry: RouteEntry {}
