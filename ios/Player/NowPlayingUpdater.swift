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
  /// Now-playing-only artwork URL template (`{id}` → track.id), set from config at setup.
  /// Applied only here — the shared `resolveArtworkUrl` / CarPlay list paths never see it.
  var nowPlayingUrlTemplate: String?
  /// Invoked when the published title/artist actually change, so the owner can emit the JS
  /// `onNowPlayingChanged` event (which it shapes with elapsed time / artwork / etc.).
  var onChanged: (@MainActor (_ track: Track, _ title: String, _ artist: String?) -> Void)?

  private var artworkLoadTask: Task<Void, Never>?
  private var artworkGeneration: UInt = 0

  /// Last published title/artist line, to dedupe redundant writes (render runs on every transition).
  private struct Published: Equatable {
    let trackId: String?
    let title: String?
    let artist: String?
  }
  private var lastPublished: Published?

  /// Bumped on every `render`; an async formatter result applies only if its render is still the
  /// latest (latest-render-wins — drops a result whose track skipped or whose state moved on while
  /// the formatter was in flight).
  private var renderGeneration: UInt = 0

  init(nowPlayingInfoController: NowPlayingInfoController) {
    self.nowPlayingInfoController = nowPlayingInfoController
  }

  /// Publishes the static, per-track fields + artwork on a track change. The title/artist line is
  /// owned by `render` (routed through `applyFields` here so it dedupes against later renders).
  func loadMetaValues(for track: Track) {
    nowPlayingInfoController.set(keyValues: [
      MediaItemProperty.albumTitle(track.album),
      NowPlayingInfoProperty.isLiveStream(track.live),
    ])
    applyFields(track: track, title: track.title, artist: track.artist)
    loadArtwork(for: track)
  }

  /// Renders the now-playing title/artist line: the default (`override ?? track`) immediately, then —
  /// when a formatter is configured — its async result overlaid (each field falling back to the
  /// default). Safe to call on every playback transition; redundant writes are deduped.
  func render(
    track: Track,
    timedMetadata: TimedMetadata?,
    playWhenReady: Bool,
    stalled: Bool,
    error: PlaybackError?,
    override: NowPlayingUpdate?,
    formatter: ((_ params: FormatNowPlayingParams) -> Promise<NowPlayingUpdate?>)?
  ) {
    renderGeneration &+= 1
    let generation = renderGeneration

    let defaultTitle = override?.title ?? track.title
    let defaultSecondary = override?.artist ?? track.artist
    applyFields(track: track, title: defaultTitle, artist: defaultSecondary)

    guard let formatter else { return }
    let params = FormatNowPlayingParams(
      track: track,
      timedMetadata: timedMetadata,
      playWhenReady: playWhenReady,
      stalled: stalled,
      error: error
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
            artist: formatted.artist ?? defaultSecondary
          )
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

  /// Stamps the title/artist (each falling back to the track's own) onto NowPlayingInfoCenter,
  /// deduped against the last publish, and notifies `onChanged`.
  private func applyFields(track: Track, title: String?, artist: String?) {
    let resolvedTitle = title ?? track.title
    let resolvedArtist = artist ?? track.artist
    let published = Published(
      trackId: track.src ?? track.url,
      title: resolvedTitle,
      artist: resolvedArtist
    )
    guard published != lastPublished else { return }
    lastPublished = published

    nowPlayingInfoController.set(keyValues: [
      MediaItemProperty.title(resolvedTitle),
      MediaItemProperty.artist(resolvedArtist),
    ])
    onChanged?(track, resolvedTitle, resolvedArtist)
  }

  private func loadArtwork(for track: Track) {
    // Now-playing-only: if a URL template is configured and the track has a non-empty id,
    // build a copy of the track whose `artwork` is the substituted URL so the SHARED
    // resolver (artworkUrlResolver / resolveArtworkUrl) picks it up through the `request`
    // layer / baseUrl. List paths always pass the original track and are unaffected.
    let resolveTrack: Track = {
      guard let template = nowPlayingUrlTemplate else { return track }
      guard let id = track.id, !id.isEmpty else { return track }
      return track.copying(artwork: template.replacingOccurrences(of: "{id}", with: id))
    }()

    let artworkUrl = resolveTrack.artworkSource?.uri ?? resolveTrack.artwork
    logger.debug("loadArtwork: \(track.title), artworkUrl: \(artworkUrl ?? "nil")")

    // Now Playing artwork: use screen width in pixels, capped at 1200px
    let screenScale = UIScreen.main.scale
    let screenWidth = UIScreen.main.bounds.width * screenScale
    let artworkSize = min(screenWidth, 1200)
    let nowPlayingSize = ImageContext(width: artworkSize, height: artworkSize)

    artworkLoadTask?.cancel()
    artworkGeneration &+= 1
    let expectedGeneration = artworkGeneration
    artworkLoadTask = Task {
      let image: UIImage?

      // Resolver provides size context for CDN optimization
      if let resolver = artworkUrlResolver,
         let imageSource = await resolver(resolveTrack, nowPlayingSize)
      {
        guard !Task.isCancelled, artworkGeneration == expectedGeneration else { return }
        logger.debug("loadArtwork: using resolved URL: \(imageSource.uri)")
        image = await ArtworkImageFetcher.fetchImage(from: imageSource)
      } else {
        guard !Task.isCancelled, artworkGeneration == expectedGeneration else { return }
        if let source = resolveTrack.artworkImageSource {
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
