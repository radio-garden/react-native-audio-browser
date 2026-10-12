import Foundation
import NitroModules

/// The browser configuration, copied out of `NativeBrowserConfiguration` once per
/// `configureBrowser`: the generated getters convert from C++ on every read, and routes are read
/// on every resolve. Matches Kotlin's BrowserConfig.
struct BrowserConfig {
  /// Base HTTP request configuration (applied to every request kind)
  var request: TransformableRequestConfig?

  /// Browse-kind request configuration (applied to every browse request,
  /// layered request → browse → route)
  var browse: TransformableRequestConfig?

  /// Resolver for the shared request layer (resolved once per content generation).
  var requestResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  /// Resolver for the browse layer (resolved once per content generation).
  var browseResolver: (() -> Promise<Promise<TransformableRequestConfig>>)?

  /// Media URL transformation configuration
  var media: MediaRequestConfig?

  /// Artwork URL transformation configuration
  var artwork: ArtworkRequestConfig?

  /// Now-playing-only artwork configuration (lock screen / CarPlay / Android Auto
  /// now-playing). A distinct kind from `artwork`; the now-playing path falls back to
  /// `artwork` when this is nil.
  var nowPlayingArtwork: ArtworkRequestConfig?

  /// Routes as array with flattened entries
  /// Includes __tabs__, __search__, and __default__ special routes
  var routes: [NativeRouteEntry]?

  /// Behavior: single track playback vs queue expansion
  var singleTrack: Bool = false

  /// Behavior: show offline error in Android Auto controller
  var androidControllerOfflineError: Bool = true

  // MARK: - CarPlay Options

  /// App-localized title for CarPlay loading screens (shown as the centered
  /// empty state while content resolves; nil leaves them blank)
  var carPlayLoadingTitle: String?

  /// Resolves a browse path for the CarPlay Now Playing album line when the
  /// active track has no albumPath. Invoked on track changes (not at tap) so
  /// the album line only becomes tappable when a destination exists.
  var resolveAlbumPath: ((_ track: Track) -> Promise<String?>)?

  /// Custom handler for track load events (overrides default load behavior)
  var handleTrackLoad: ((_ event: TrackLoadEvent) -> Promise<Promise<Void>>)?

  /// Callback to customize navigation error display (for i18n)
  /// Used by CarPlay and available via `useFormattedNavigationError()` for app UI.
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
    androidControllerOfflineError = config.androidControllerOfflineError ?? true
    carPlayLoadingTitle = config.carPlayLoadingTitle
    resolveAlbumPath = config.resolveAlbumPath
    formatNavigationError = config.formatNavigationError
  }

  /// A copy of this config with every JS-provided function dropped — at every
  /// level, not just the top; each nested type's strip below nils its own —
  /// keeping the static fields (URLs, headers, routes' shape). Makes the config
  /// safe to hold after its JS runtime is gone: callers take their
  /// static/built-in fallback paths (a callback-only route degrades to its
  /// no-content path). See `HybridAudioBrowser.dropStaleJSCallbacks` for why
  /// invoking a stale callback is fatal.
  func strippingJSCallbacks() -> BrowserConfig {
    var stripped = self
    stripped.request = request?.strippingJSCallbacks()
    stripped.browse = browse?.strippingJSCallbacks()
    stripped.requestResolver = nil
    stripped.browseResolver = nil
    stripped.media = media?.strippingJSCallbacks()
    stripped.artwork = artwork?.strippingJSCallbacks()
    stripped.nowPlayingArtwork = nowPlayingArtwork?.strippingJSCallbacks()
    stripped.routes = routes?.map { $0.strippingJSCallbacks() }
    stripped.handleTrackLoad = nil
    stripped.resolveAlbumPath = nil
    stripped.formatNavigationError = nil
    return stripped
  }
}

// MARK: - NativeRouteEntry + RouteEntry conformance

extension NativeRouteEntry: RouteEntry {}

// MARK: - Deep JS-callback stripping

// The generated structs are get-only from Swift, so each strip rebuilds the
// value through the memberwise initializer with the function fields nil'd.

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
