import Foundation
import MediaPlayer
import NitroModules
import os.log

/// Owns the now-playing surface: publishes a track's static fields + artwork, and renders the
/// title/artist line — the formatter's result, or the track/override default. The single writer of
/// the title/artist fields, with a publish-dedupe so the frequent re-renders stay cheap.
///
/// Does not exclusively own NowPlayingInfoController -- other callers (e.g. HybridAudioBrowser for
/// overrides) can still access it directly.
@MainActor
final class NowPlayingUpdater {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingUpdater")
  private let nowPlayingInfoController: NowPlayingInfoController

  var artworkUrlResolver: ((Track, ImageContext?) async -> ImageSource?)?
  /// Invoked when the published title/artist actually change, so the owner can emit the JS
  /// `onNowPlayingChanged` event (which it shapes with elapsed time / artwork / etc.).
  var onChanged: (@MainActor (_ track: Track, _ title: String, _ artist: String?, _ album: String?) -> Void)?

  private var artworkLoadTask: Task<Void, Never>?
  private var artworkGeneration: UInt = 0

  /// Last published text lines, to dedupe redundant writes (render runs on every transition).
  private struct Published: Equatable {
    let trackId: String?
    let title: String?
    let artist: String?
    let album: String?
  }

  /// Dedupe baseline for `applyFields`. Only meaningful while it describes the
  /// controller's current info dict, so the two are cleared together in
  /// `clearNowPlaying()` — never separately.
  private var lastPublished: Published?

  /// Empties the now-playing surface and the dedupe baseline that describes it.
  /// One call, because clearing the dict without the baseline makes the next
  /// publish of the SAME track dedupe against a dict that no longer holds it —
  /// artwork lands (never deduped) but the text doesn't, and `onChanged`
  /// never fires.
  func clearNowPlaying() {
    nowPlayingInfoController.clear()
    lastPublished = nil
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
  /// lines are owned by `render` (routed through `applyFields` here so it dedupes against later
  /// renders).
  func loadMetaValues(for track: Track) {
    nowPlayingInfoController.set(keyValues: [
      NowPlayingInfoProperty.isLiveStream(track.live),
    ])
    applyFields(track: track, title: track.title, artist: track.artist, album: track.album)
    loadArtwork(for: track)
  }

  /// Renders the now-playing text lines (title, artist, album): the default (`override ?? track`)
  /// immediately, then — when a formatter is configured — its async result overlaid (each field
  /// falling back to the default). A `flash` (transient line, e.g. feedback for a refused remote
  /// command) outranks both: while one is active the formatter pass is skipped entirely, so its
  /// async result can't land on top. Safe to call on every playback transition; redundant writes
  /// are deduped.
  func render(
    track: Track,
    timedMetadata: TimedMetadata?,
    playWhenReady: Bool,
    stalled: StallReason?,
    error: PlaybackError?,
    flash: NowPlayingUpdate? = nil,
    override: NowPlayingUpdate?,
    formatter: ((_ params: FormatNowPlayingParams) -> Promise<NowPlayingUpdate?>)?,
  ) {
    renderGeneration &+= 1
    let generation = renderGeneration

    let defaultTitle = override?.title ?? track.title
    let defaultSecondary = override?.artist ?? track.artist
    let defaultAlbum = override?.album ?? track.album

    let trackId = track.identity ?? track.path

    if let flash {
      applyFields(
        track: track,
        title: flash.title ?? defaultTitle,
        artist: flash.artist ?? defaultSecondary,
        album: flash.album ?? defaultAlbum,
      )
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
      applyFields(track: track, title: defaultTitle, artist: defaultSecondary, album: defaultAlbum)
    }

    guard let formatter else { return }
    let params = FormatNowPlayingParams(
      track: track,
      timedMetadata: timedMetadata,
      playWhenReady: playWhenReady,
      stalled: stalled,
      error: error,
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
          self.applyFields(
            track: track,
            title: formatted.title ?? defaultTitle,
            artist: formatted.artist ?? defaultSecondary,
            album: formatted.album ?? defaultAlbum,
          )
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

  /// Stamps the title/artist/album (each falling back to the track's own) onto
  /// NowPlayingInfoCenter, deduped against the last publish, and notifies `onChanged`.
  private func applyFields(track: Track, title: String?, artist: String?, album: String?) {
    let resolvedTitle = title ?? track.title
    let resolvedArtist = artist ?? track.artist
    let resolvedAlbum = album ?? track.album
    let published = Published(
      trackId: track.identity ?? track.path,
      title: resolvedTitle,
      artist: resolvedArtist,
      album: resolvedAlbum,
    )
    guard published != lastPublished else { return }
    lastPublished = published

    nowPlayingInfoController.set(keyValues: [
      MediaItemProperty.title(resolvedTitle),
      MediaItemProperty.artist(resolvedArtist),
      MediaItemProperty.albumTitle(resolvedAlbum),
    ])
    onChanged?(track, resolvedTitle, resolvedArtist, resolvedAlbum)
  }

  private func loadArtwork(for track: Track) {
    // Now-playing is a single image with no appearance to key off, so a pair
    // collapses to its dark URL (see `Variant_String_ArtworkVariants.url`).
    let artworkUrl = track.artworkSource?.uri ?? track.artwork?.url
    logger.debug("loadArtwork: \(track.title), artworkUrl: \(artworkUrl ?? "nil")")

    let artworkSize = Self.artworkPixelWidth
    let nowPlayingSize = ImageContext(width: artworkSize, height: artworkSize)

    artworkLoadTask?.cancel()
    artworkGeneration &+= 1
    let expectedGeneration = artworkGeneration
    artworkLoadTask = Task {
      let image: UIImage?

      // Resolver provides size context for CDN optimization
      if let resolver = artworkUrlResolver,
         let imageSource = await resolver(track, nowPlayingSize)
      {
        guard !Task.isCancelled, artworkGeneration == expectedGeneration else { return }
        logger.debug("loadArtwork: using resolved URL: \(imageSource.uri)")
        image = await ArtworkImageFetcher.fetchImage(from: imageSource)
      } else {
        guard !Task.isCancelled, artworkGeneration == expectedGeneration else { return }
        if let source = track.artworkImageSource {
          image = await ArtworkImageFetcher.fetchImage(from: source)
        } else {
          image = nil
        }
      }

      guard !Task.isCancelled, artworkGeneration == expectedGeneration else { return }

      if let image {
        logger.debug("loadArtwork: loaded image \(image.size.width)x\(image.size.height)")
        // Note: The requestHandler closure is called from MediaPlayer's background queue,
        // so we must mark it @Sendable to break @MainActor isolation inheritance.
        let artwork = MPMediaItemArtwork(boundsSize: image.size) { @Sendable requestedSize in
          return image
        }
        nowPlayingInfoController.set(keyValue: MediaItemProperty.artwork(artwork))
      } else {
        logger.debug("loadArtwork: no image loaded")
        nowPlayingInfoController.set(keyValue: MediaItemProperty.artwork(nil))
      }
    }
  }
}
