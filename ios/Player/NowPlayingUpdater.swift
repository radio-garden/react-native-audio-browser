import Foundation
import MediaPlayer
import NitroModules
import os.log

/// What the now-playing surface renders from: the player's track and state.
@MainActor
protocol NowPlayingSource: AnyObject {
  var currentTrack: Track? { get }
  var currentTime: Double { get }
  var playWhenReady: Bool { get }
  var isStalled: Bool { get }
  var nitroPlaybackError: PlaybackError? { get }
}

/// Owns the now-playing surface: publishes a track's static fields + artwork, and renders the
/// title/artist line — a flash, else the formatter's result, else the override or the track's own
/// fields. Holds that state (override, flash, latest timed metadata, formatter) and reports each
/// change of the rendered lines through `onChanged`.
@MainActor
final class NowPlayingUpdater {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingUpdater")
  private let nowPlayingInfoController: NowPlayingInfoController

  weak var source: NowPlayingSource?
  var artworkUrlResolver: ((Track, ImageContext?) async -> ImageSource?)?
  /// Called with the JS-facing metadata whenever the rendered lines or the track change.
  var onChanged: ((NowPlayingMetadata) -> Void)?

  /// When false the surface shows the track's own fields: the override and formatter are ignored.
  var enabled = true
  var formatter: ((_ params: FormatNowPlayingParams) -> Promise<NowPlayingUpdate?>)?

  /// Whether the device is online, which tells a stall apart: offline or buffering.
  var isOnline = true {
    didSet { if isOnline != oldValue { render() } }
  }

  private var override: NowPlayingUpdate?
  private var timedMetadata: TimedMetadata?
  /// A transient line (e.g. feedback for a refused remote command) that outranks the formatter
  /// and the override, reverted by a native timer: JS timers pause in the background.
  private var flash: NowPlayingUpdate?
  private var flashRevert: DispatchWorkItem?

  private var artworkLoadTask: Task<Void, Never>?

  /// The track `onChanged` last reported, so a new track with the same text still reports.
  private var publishedTrackId: String?

  func setOverride(_ update: NowPlayingUpdate?) {
    override = update
    render()
  }

  func flash(_ update: NowPlayingUpdate, durationMs: Double) {
    flashRevert?.cancel()
    flash = update
    let revert = DispatchWorkItem { [weak self] in
      self?.flash = nil
      self?.flashRevert = nil
      self?.render()
    }
    flashRevert = revert
    DispatchQueue.main.asyncAfter(deadline: .now() + durationMs / 1000, execute: revert)
    render()
  }

  func clearFlash() {
    guard flash != nil else { return }
    cancelFlash()
    render()
  }

  func receivedTimedMetadata(_ metadata: TimedMetadata) {
    timedMetadata = metadata
    render()
  }

  /// A new track drops the override, flash and timed metadata of the last one.
  func trackChanged() {
    override = nil
    cancelFlash()
    timedMetadata = nil
    render()
  }

  /// The metadata JS sees for the current track, before any formatter result.
  /// Applies the override whether or not `enabled` is set.
  var nowPlaying: NowPlayingMetadata? {
    guard let track = source?.currentTrack else { return nil }
    return metadata(track: track, lines: .resolve(track: track, flash: flash, override: override))
  }

  /// Empties the now-playing surface and drops any artwork fetch or formatter result in flight.
  func clearNowPlaying() {
    renderGeneration &+= 1
    artworkLoadTask?.cancel()
    artworkLoadTask = nil
    nowPlayingInfoController.clear()
    publishedTrackId = nil
    formattedTrackId = nil
  }

  /// Bumped on every `render`; an async formatter result applies only if its render is still the
  /// latest (latest-render-wins — drops a result whose track skipped or whose state moved on while
  /// the formatter was in flight).
  private var renderGeneration: UInt = 0

  /// The track whose *formatted* line is currently on the surface. While this
  /// matches the rendering track, `render` skips its interim default write — see
  /// the comment at that site. Cleared whenever the surface stops showing a
  /// formatter result (a flash overwrites it, `clearNowPlaying` empties it).
  private var formattedTrackId: String?

  init(nowPlayingInfoController: NowPlayingInfoController) {
    self.nowPlayingInfoController = nowPlayingInfoController
  }

  /// Pixel width to request now-playing artwork at: the active scene's screen
  /// width in pixels, capped at `maxArtworkPixelWidth`. With no foreground
  /// scene (headless CarPlay) the cap stands in — every current device exceeds
  /// it anyway, and this only sizes a CDN request.
  private static var artworkPixelWidth: CGFloat {
    guard let screen = UIApplication.shared.activeWindowScene?.screen else {
      return maxArtworkPixelWidth
    }
    return min(screen.bounds.width * screen.scale, maxArtworkPixelWidth)
  }

  /// Mirrored by the Kotlin twin's `artworkSizePx`.
  private static let maxArtworkPixelWidth: CGFloat = 1200

  /// Publishes the static, per-track fields + artwork on a track change. The title/artist/album
  /// lines are owned by `render` (routed through `apply` here so it dedupes against later
  /// renders).
  func loadMetaValues(for track: Track) {
    nowPlayingInfoController.set([MPNowPlayingInfoPropertyIsLiveStream: track.live])
    apply(.resolve(track: track), for: track)
    loadArtwork(for: track)
  }

  /// Renders the now-playing text lines (title, artist, album): the default (`override ?? track`)
  /// immediately, then — when a formatter is configured — its async result overlaid (each field
  /// falling back to the default). A `flash` (transient line, e.g. feedback for a refused remote
  /// command) outranks both: while one is active the formatter pass is skipped entirely, so its
  /// async result can't land on top. Called on every playback transition; unchanged lines are not
  /// re-published.
  func render() {
    guard let source, let track = source.currentTrack else { return }
    let override = enabled ? self.override : nil
    let formatter = enabled ? self.formatter : nil
    renderGeneration &+= 1
    let generation = renderGeneration

    let trackId = track.identity ?? track.path

    if let flash {
      apply(.resolve(track: track, flash: flash, override: override), for: track)
      // The flash replaced the formatter's line, so the surface no longer shows
      // one: the render that follows the flash reverting must publish the default
      // immediately rather than sit on stale flash text awaiting the formatter.
      formattedTrackId = nil
      return
    }

    // Publish the raw default only while no formatter result is on screen for this
    // track. `render` runs on EVERY playback transition (state change, playing/
    // buffering flip, timed-metadata tick, connectivity change); writing the
    // default each time would flash the track title over the formatter's line
    // until the async result lands, since the two differ by construction on a live
    // stream (station name vs. song line).
    if formatter == nil || formattedTrackId != trackId {
      apply(.resolve(track: track, override: override), for: track)
    }

    guard let formatter else { return }
    let params = FormatNowPlayingParams(
      track: track,
      timedMetadata: timedMetadata,
      playWhenReady: source.playWhenReady,
      stalled: source.isStalled ? (isOnline ? .buffering : .offline) : nil,
      error: source.nitroPlaybackError,
    )
    // `@Sendable` breaks the @MainActor isolation inheritance: Nitro resolves the promise on the JS
    // thread, so the resolver must NOT be main-isolated (a main-isolated closure run off-main traps
    // in `_swift_task_checkIsolated`). Hop back to the main actor to apply (same pattern as the
    // artwork callback below).
    formatter(params)
      .then { @Sendable [weak self] formatted in
        guard let self, let formatted else { return }
        Task { @MainActor in
          // Apply only if no newer render has superseded this one (fast skip / state moved on).
          guard self.renderGeneration == generation else { return }
          self.apply(.resolve(track: track, formatted: formatted, override: override), for: track)
          // A formatter result now owns the surface for this track, so later
          // renders skip the interim default write.
          self.formattedTrackId = trackId
        }
      }
      .catch { @Sendable [weak self] error in
        let message = error.localizedDescription
        Task { @MainActor in
          self?.logger.error("nowPlayingMetadataFormatter failed: \(message)")
        }
      }
  }

  // MARK: - Private

  /// Stamps the lines onto the now-playing surface, and notifies `onChanged` when the text or the
  /// track changed.
  private func apply(_ lines: NowPlayingLines, for track: Track) {
    let textChanged = nowPlayingInfoController.set([
      MPMediaItemPropertyTitle: lines.title,
      MPMediaItemPropertyArtist: lines.artist,
      MPMediaItemPropertyAlbumTitle: lines.album,
    ])
    let trackId = track.identity ?? track.path
    guard textChanged || trackId != publishedTrackId else { return }
    publishedTrackId = trackId
    onChanged?(metadata(track: track, lines: lines))
  }

  private func cancelFlash() {
    flashRevert?.cancel()
    flashRevert = nil
    flash = nil
  }

  private func metadata(track: Track, lines: NowPlayingLines) -> NowPlayingMetadata {
    NowPlayingMetadata(
      elapsedTime: source?.currentTime,
      title: lines.title,
      album: lines.album,
      artist: lines.artist,
      duration: track.duration,
      artwork: Self.jsArtwork(for: track),
      description: track.description,
      mediaId: track.src ?? track.path,
      genre: track.genre,
    )
  }

  /// The artwork URI JS can load: the resolved `artworkSource` (a rendered SF Symbol's `file://`
  /// included), else the raw artwork unless it is an SF Symbol.
  private static func jsArtwork(for track: Track) -> String? {
    if let uri = track.artworkSource?.uri { return uri }
    guard let artwork = track.artwork?.url else { return nil }
    return SFSymbolRenderer.isSFSymbol(artwork) ? nil : artwork
  }

  private func loadArtwork(for track: Track) {
    // Now-playing is a single image with no appearance to key off, so a pair
    // collapses to its dark URL (see `Variant_String_ArtworkVariants.url`).
    let artworkUrl = track.artworkSource?.uri ?? track.artwork?.url
    logger.debug("loadArtwork: \(track.title), artworkUrl: \(artworkUrl ?? "nil")")

    let artworkSize = Self.artworkPixelWidth
    let nowPlayingSize = ImageContext(width: artworkSize, height: artworkSize)

    artworkLoadTask?.cancel()
    artworkLoadTask = Task {
      let image: UIImage?

      // Resolver provides size context for CDN optimization
      if let resolver = artworkUrlResolver,
         let imageSource = await resolver(track, nowPlayingSize)
      {
        guard !Task.isCancelled else { return }
        logger.debug("loadArtwork: using resolved URL: \(imageSource.uri)")
        image = await ArtworkImageFetcher.fetchImage(from: imageSource, waitsForConnectivity: true)
      } else {
        guard !Task.isCancelled else { return }
        if let source = track.artworkImageSource {
          image = await ArtworkImageFetcher.fetchImage(from: source, waitsForConnectivity: true)
        } else {
          image = nil
        }
      }

      guard !Task.isCancelled else { return }

      if let image {
        logger.debug("loadArtwork: loaded image \(image.size.width)x\(image.size.height)")
        // Note: The requestHandler closure is called from MediaPlayer's background queue,
        // so we must mark it @Sendable to break @MainActor isolation inheritance.
        let artwork = MPMediaItemArtwork(boundsSize: image.size) { @Sendable requestedSize in
          return image
        }
        nowPlayingInfoController.set([MPMediaItemPropertyArtwork: artwork])
      } else {
        logger.debug("loadArtwork: no image loaded")
        nowPlayingInfoController.set([MPMediaItemPropertyArtwork: nil])
      }
    }
  }
}
