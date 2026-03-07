import Foundation
import Kingfisher
import MediaPlayer
import NitroModules
import os.log

/// Does not exclusively own NowPlayingInfoController -- other callers
/// (e.g. HybridAudioBrowser for overrides) can still access it directly.
@MainActor
final class NowPlayingUpdater {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingUpdater")
  private let nowPlayingInfoController: NowPlayingInfoController

  var artworkUrlResolver: ((Track, ImageContext?) async -> ImageSource?)?

  private var artworkLoadTask: Task<Void, Never>?
  private var artworkGeneration: UInt = 0

  init(nowPlayingInfoController: NowPlayingInfoController) {
    self.nowPlayingInfoController = nowPlayingInfoController
  }

  func loadMetaValues(for track: Track, rate: Float) {
    nowPlayingInfoController.set(keyValues: [
      MediaItemProperty.artist(track.artist),
      MediaItemProperty.title(track.title),
      MediaItemProperty.albumTitle(track.album),
      NowPlayingInfoProperty.playbackRate(Double(rate)),
      NowPlayingInfoProperty.isLiveStream(track.live),
    ])
    loadArtwork(for: track)
  }

  func updatePlaybackValues(duration: Double, rate: Float, currentTime: Double) {
    logger.debug("updatePlaybackValues: duration=\(duration), rate=\(rate), currentTime=\(currentTime)")
    nowPlayingInfoController.set(keyValues: [
      MediaItemProperty.duration(duration),
      NowPlayingInfoProperty.playbackRate(Double(rate)),
      NowPlayingInfoProperty.elapsedPlaybackTime(currentTime),
    ])
  }

  /// Separate from playbackRate — required for CarPlay to show correct play/pause button.
  func updatePlaybackState(playWhenReady: Bool) {
    let state: MPNowPlayingPlaybackState = playWhenReady ? .playing : .paused
    logger.debug("updatePlaybackState: \(state.rawValue) (playWhenReady=\(playWhenReady))")
    nowPlayingInfoController.setPlaybackState(state)
  }

  func setCurrentTime(seconds: Double) {
    nowPlayingInfoController.set(
      keyValue: NowPlayingInfoProperty.elapsedPlaybackTime(seconds),
    )
  }

  // MARK: - Private

  private func loadArtwork(for track: Track) {
    let artworkUrl = track.artworkSource?.uri ?? track.artwork
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
         let imageSource = await resolver(track, nowPlayingSize)
      {
        guard !Task.isCancelled, artworkGeneration == expectedGeneration else { return }
        logger.debug("loadArtwork: using resolved URL: \(imageSource.uri)")
        image = await loadImage(from: imageSource)
      } else {
        guard !Task.isCancelled, artworkGeneration == expectedGeneration else { return }
        image = await track.loadArtwork()
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

  private func loadImage(from imageSource: ImageSource) async -> UIImage? {
    guard let url = URL(string: imageSource.uri) else { return nil }

    var options: KingfisherOptionsInfo = []
    if let headers = imageSource.headers, !headers.isEmpty {
      let modifier = AnyModifier { request in
        var mutableRequest = request
        for (key, value) in headers {
          mutableRequest.setValue(value, forHTTPHeaderField: key)
        }
        return mutableRequest
      }
      options.append(.requestModifier(modifier))
    }

    do {
      let result = try await KingfisherManager.shared.retrieveImage(with: url, options: options)
      return result.image
    } catch {
      logger.error("Failed to load artwork from \(imageSource.uri): \(error.localizedDescription)")
      return nil
    }
  }
}
