import Foundation
import NitroModules
import os.log

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
  let artwork: ArtworkRequestConfig?

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

  /// Custom handler for track load events (overrides default load behavior)
  let handleTrackLoad: ((_ event: TrackLoadEvent) -> Promise<Promise<Void>>)?

  /// Callback to customize navigation error display (for i18n)
  /// Used by CarPlay and available via `useFormattedNavigationError()` for app UI.
  let formatNavigationError: ((_ params: FormatNavigationErrorParams) -> Promise<FormattedNavigationError?>)?

  init(
    request: TransformableRequestConfig? = nil,
    media: MediaRequestConfig? = nil,
    artwork: ArtworkRequestConfig? = nil,
    routes: [NativeRouteEntry]? = nil,
    singleTrack: Bool = false,
    handleTrackLoad: ((_ event: TrackLoadEvent) -> Promise<Promise<Void>>)? = nil,
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
    self.handleTrackLoad = handleTrackLoad
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
    handleTrackLoad = config.handleTrackLoad
    androidControllerOfflineError = config.androidControllerOfflineError ?? true
    carPlayUpNextButton = config.carPlayUpNextButton ?? true
    carPlayNowPlayingButtons = config.carPlayNowPlayingButtons ?? []
    formatNavigationError = config.formatNavigationError
  }

  // MARK: - Track Load Handler

  private static let logger = Logger(subsystem: "com.audiobrowser", category: "BrowserConfig")

  /// Awaits the handleTrackLoad callback using the double-Promise pattern required by Nitro.
  ///
  /// Nitro wraps value-returning JS callbacks in Promise<T> for thread safety, so a JS callback
  /// returning Promise<void> becomes Promise<Promise<Void>> on the native side. Both layers
  /// must be awaited to properly wait for the JS work to complete.
  ///
  /// - Returns: true if the handler was present and invoked, false if not configured
  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool {
    guard let handler = handleTrackLoad else { return false }
    do {
      // MainActor: Nitro bridge call must be on main thread (C++ noexcept)
      nonisolated(unsafe) let _handler = handler
      nonisolated(unsafe) let _event = event
      let outerPromise = await MainActor.run { _handler(_event) }
      let innerPromise = try await outerPromise.await()
      try await innerPromise.await()
    } catch {
      Self.logger.error("handleTrackLoad failed: \(error.localizedDescription)")
    }
    return true
  }
}

// MARK: - NativeRouteEntry + RouteEntry conformance

extension NativeRouteEntry: RouteEntry {}
