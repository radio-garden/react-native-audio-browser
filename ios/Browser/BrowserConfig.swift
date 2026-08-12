import Foundation
import NitroModules
import os.log

/// Flattened browser configuration (matches Kotlin BrowserConfig).
///
/// This is a convenience wrapper around NativeBrowserConfiguration that
/// provides easier access to configuration properties.
struct BrowserConfig {
  /// Base HTTP request configuration (applied to every request kind)
  let request: TransformableRequestConfig?

  /// Browse-kind request configuration (applied to every browse request,
  /// layered request → browse → route)
  let browse: TransformableRequestConfig?

  /// Resolver for the shared request layer (resolved once per content generation).
  let requestResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  /// Resolver for the browse layer (resolved once per content generation).
  let browseResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  /// Media URL transformation configuration
  let media: MediaRequestConfig?

  /// Artwork URL transformation configuration
  let artwork: ArtworkRequestConfig?

  /// Now-playing-only artwork configuration (lock screen / CarPlay / Android Auto
  /// now-playing). A distinct kind from `artwork`; the now-playing path falls back to
  /// `artwork` when this is nil.
  let nowPlayingArtwork: ArtworkRequestConfig?

  /// Routes as array with flattened entries
  /// Includes __tabs__, __search__, and __default__ special routes
  let routes: [NativeRouteEntry]?

  /// Behavior: single track playback vs queue expansion
  let singleTrack: Bool

  /// Behavior: show offline error in Android Auto controller
  let androidControllerOfflineError: Bool

  // MARK: - CarPlay Options

  /// App-localized title for CarPlay loading screens (shown as the centered
  /// empty state while content resolves; nil leaves them blank)
  let carPlayLoadingTitle: String?

  /// Resolves a browse path for the CarPlay Now Playing album line when the
  /// active track has no albumUrl. Invoked on track changes (not at tap) so
  /// the album line only becomes tappable when a destination exists.
  let resolveAlbumUrl: ((_ track: Track) -> Promise<String?>)?

  /// Custom handler for track load events (overrides default load behavior)
  let handleTrackLoad: ((_ event: TrackLoadEvent) -> Promise<Promise<Void>>)?

  /// Callback to customize navigation error display (for i18n)
  /// Used by CarPlay and available via `useFormattedNavigationError()` for app UI.
  let formatNavigationError: ((_ params: FormatNavigationErrorParams) -> Promise<FormattedNavigationError?>)?

  init(
    request: TransformableRequestConfig? = nil,
    browse: TransformableRequestConfig? = nil,
    requestResolver: (() -> Promise<Promise<TransformableRequestConfig>>)? = nil,
    browseResolver: (() -> Promise<Promise<TransformableRequestConfig>>)? = nil,
    media: MediaRequestConfig? = nil,
    artwork: ArtworkRequestConfig? = nil,
    nowPlayingArtwork: ArtworkRequestConfig? = nil,
    routes: [NativeRouteEntry]? = nil,
    singleTrack: Bool = false,
    handleTrackLoad: ((_ event: TrackLoadEvent) -> Promise<Promise<Void>>)? = nil,
    androidControllerOfflineError: Bool = true,
    carPlayLoadingTitle: String? = nil,
    resolveAlbumUrl: ((_ track: Track) -> Promise<String?>)? = nil,
    formatNavigationError: ((_ params: FormatNavigationErrorParams) -> Promise<FormattedNavigationError?>)? = nil,
  ) {
    self.request = request
    self.browse = browse
    self.requestResolver = requestResolver
    self.browseResolver = browseResolver
    self.media = media
    self.artwork = artwork
    self.nowPlayingArtwork = nowPlayingArtwork
    self.routes = routes
    self.singleTrack = singleTrack
    self.handleTrackLoad = handleTrackLoad
    self.androidControllerOfflineError = androidControllerOfflineError
    self.carPlayLoadingTitle = carPlayLoadingTitle
    self.resolveAlbumUrl = resolveAlbumUrl
    self.formatNavigationError = formatNavigationError
  }

  /// Create from NativeBrowserConfiguration
  init(from config: NativeBrowserConfiguration) {
    request = config.request
    browse = config.browse
    requestResolver = config.requestResolver
    browseResolver = config.browseResolver
    media = config.media
    artwork = config.artwork
    nowPlayingArtwork = config.nowPlayingArtwork
    routes = config.routes
    singleTrack = config.singleTrack ?? false
    handleTrackLoad = config.handleTrackLoad
    androidControllerOfflineError = config.androidControllerOfflineError ?? true
    carPlayLoadingTitle = config.carPlayLoadingTitle
    resolveAlbumUrl = config.resolveAlbumUrl
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
