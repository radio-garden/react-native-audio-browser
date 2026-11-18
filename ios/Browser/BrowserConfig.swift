import Foundation
import NitroModules

/// The browser configuration, copied out of `NativeBrowserConfiguration` once per configure: the
/// generated getters convert from C++ on every read, and routes are read on every resolve.
struct BrowserConfig {
  var request: TransformableRequestConfig?

  var browse: TransformableRequestConfig?

  var requestResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  var browseResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  var media: MediaRequestConfig?

  var artwork: ArtworkRequestConfig?

  /// Falls back to `artwork` when nil.
  var nowPlayingArtwork: ArtworkRequestConfig?

  /// Includes the `__tabs__`, `__search__` and `__default__` routes (`SimpleRouter`).
  var routes: [NativeRouteEntry]?

  /// Plays only the tapped track instead of queueing its section (ADR 0006).
  var singleTrack: Bool = false

  // MARK: - CarPlay Options

  var carPlayLoadingTitle: String?

  var resolveAlbumPath: ((_ track: Track) -> Promise<String?>)?

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

  /// A copy with every JS function dropped, nested ones included, keeping the static fields. Safe
  /// to hold once its JS runtime is gone, where invoking a callback aborts the process
  /// (`HybridAudioBrowser.dropStaleJSCallbacks`).
  func strippingJSCallbacks() -> BrowserConfig {
    // Built from empty: a static field added later is dropped unless it is copied here.
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
