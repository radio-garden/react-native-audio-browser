import Foundation
import NitroModules

/// Copied out of `NativeBrowserConfiguration` once per `configureBrowser`: the generated getters
/// convert from C++ on every read, and routes are read on every resolve. Matches Kotlin's
/// BrowserConfig.
struct BrowserConfig {
  var request: TransformableRequestConfig?

  /// Layered request → browse → route.
  var browse: TransformableRequestConfig?

  /// Resolved once per content generation.
  var requestResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  /// Resolved once per content generation.
  var browseResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  var media: MediaRequestConfig?

  var artwork: ArtworkRequestConfig?

  /// Lock screen, CarPlay and Android Auto now playing; falls back to `artwork` when nil.
  var nowPlayingArtwork: ArtworkRequestConfig?

  /// Includes the `__tabs__`, `__search__` and `__default__` special routes.
  var routes: [NativeRouteEntry]?

  /// Plays only the selected track instead of expanding its siblings into the queue.
  var singleTrack: Bool = false

  // MARK: - CarPlay Options

  /// The empty state shown while CarPlay content resolves; nil leaves it blank.
  var carPlayLoadingTitle: String?

  /// The browse path for the Now Playing album line when the track has no `albumPath`. Called on
  /// track change, not at tap, so the line is tappable only when a destination exists.
  var resolveAlbumPath: ((_ track: Track) -> Promise<String?>)?

  /// Replaces the default track load behaviour.
  var handleTrackLoad: ((_ event: TrackLoadEvent) -> Promise<Promise<Void>>)?

  var formatNavigationError: ((_ params: FormatNavigationErrorParams) -> Promise<FormattedNavigationError?>)?

  init() {}

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
    carPlayLoadingTitle = config.carPlayLoadingTitle
    resolveAlbumPath = config.resolveAlbumPath
    formatNavigationError = config.formatNavigationError
  }

  /// A copy with every JS function dropped, nested ones included, keeping the static fields.
  /// Safe to hold after its JS runtime is gone, where invoking a callback aborts the process
  /// (see `HybridAudioBrowser.dropStaleJSCallbacks`); callers take their static fallbacks.
  func strippingJSCallbacks() -> BrowserConfig {
    // Built from empty: a field added later is dropped unless it is copied here.
    var stripped = BrowserConfig()
    stripped.request = request?.strippingJSCallbacks()
    stripped.browse = browse?.strippingJSCallbacks()
    stripped.media = media?.strippingJSCallbacks()
    stripped.artwork = artwork?.strippingJSCallbacks()
    stripped.nowPlayingArtwork = nowPlayingArtwork?.strippingJSCallbacks()
    stripped.routes = routes?.map { $0.strippingJSCallbacks() }
    stripped.singleTrack = singleTrack
    stripped.carPlayLoadingTitle = carPlayLoadingTitle
    return stripped
  }
}

// MARK: - NativeRouteEntry + RouteEntry conformance

extension NativeRouteEntry: RouteEntry {}

// MARK: - Deep JS-callback stripping

// The generated structs are get-only from Swift, so each strip rebuilds the value through the
// memberwise initializer with the function fields nil.

private extension TransformableRequestConfig {
  func strippingJSCallbacks() -> TransformableRequestConfig {
    TransformableRequestConfig(
      transform: nil,
      transformSync: nil,
      method: method,
      path: path,
      baseUrl: baseUrl,
      headers: headers,
      query: query,
      body: body,
      contentType: contentType,
      userAgent: userAgent,
    )
  }
}

private extension MediaRequestConfig {
  func strippingJSCallbacks() -> MediaRequestConfig {
    MediaRequestConfig(
      resolve: nil,
      resolveSync: nil,
      transform: nil,
      transformSync: nil,
      method: method,
      path: path,
      baseUrl: baseUrl,
      headers: headers,
      query: query,
      body: body,
      contentType: contentType,
      userAgent: userAgent,
    )
  }
}

private extension ArtworkRequestConfig {
  func strippingJSCallbacks() -> ArtworkRequestConfig {
    ArtworkRequestConfig(
      resolve: nil,
      resolveSync: nil,
      transform: nil,
      transformSync: nil,
      imageQueryParams: imageQueryParams,
      method: method,
      path: path,
      baseUrl: baseUrl,
      headers: headers,
      query: query,
      body: body,
      contentType: contentType,
      userAgent: userAgent,
    )
  }
}

private extension NativeRouteEntry {
  func strippingJSCallbacks() -> NativeRouteEntry {
    NativeRouteEntry(
      path: path,
      browseCallback: nil,
      browseConfig: browseConfig?.strippingJSCallbacks(),
      browseStatic: browseStatic,
      searchCallback: nil,
      searchConfig: searchConfig?.strippingJSCallbacks(),
      media: media?.strippingJSCallbacks(),
      artwork: artwork?.strippingJSCallbacks(),
    )
  }
}
