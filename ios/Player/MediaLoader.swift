@preconcurrency import AVFoundation
import Foundation
import os.log

struct MediaResolvedUrl {
  let url: String
  let headers: [String: String]?
  let userAgent: String?
}

@MainActor
final class MediaLoader {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "MediaLoader")

  var mediaUrlResolver: ((String) async -> MediaResolvedUrl)?

  /// Internal access so TrackPlayer can read it for observer guards.
  private(set) var asset: AVURLAsset?

  /// Milliseconds; applied to AVPlayerItem's preferredForwardBufferDuration.
  var bufferDuration: Double = 0

  weak var delegate: MediaLoaderDelegate?

  private var mediaResolverTask: Task<Void, Never>?
  private var metadataLoadTask: Task<Void, Never>?
  private var playableLoadTask: Task<Void, Never>?
  private var url: URL?
  private var urlOptions: [String: Any]?

  // MARK: - Public API

  func resolveAndLoad(src: String) {
    if let resolver = mediaUrlResolver {
      mediaResolverTask?.cancel()
      mediaResolverTask = Task {
        self.logger.debug("resolveAndLoad: starting resolution for \(src)")

        guard !Task.isCancelled else {
          self.logger.debug("resolveAndLoad: cancelled before start")
          return
        }

        self.logger.debug("resolveAndLoad: calling resolver...")
        let resolved = await resolver(src)

        guard !Task.isCancelled else {
          self.logger.debug("resolveAndLoad: cancelled after resolver returned")
          return
        }

        self.logger.debug("resolveAndLoad: resolver returned, resolved URL: \(resolved.url)")
        if let headers = resolved.headers {
          self.logger.debug("  headers: \(headers)")
        }
        if let userAgent = resolved.userAgent {
          self.logger.debug("  userAgent: \(userAgent)")
        }

        await MainActor.run {
          guard !Task.isCancelled else {
            self.logger.debug("resolveAndLoad: cancelled before loadWithResolvedUrl")
            return
          }
          self.loadWithResolvedUrl(resolved)
        }
      }
    } else {
      let resolved = MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
      loadWithResolvedUrl(resolved)
    }
  }

  func loadAsset() {
    guard let url else { return }
    let pendingAsset = AVURLAsset(url: url, options: urlOptions)
    asset = pendingAsset

    // Separate from playable loading to allow playback to start faster
    metadataLoadTask = Task { [weak self] in
      guard let self else { return }

      guard let (commonMetadata, chapterLocales, metadataFormats) = try? await pendingAsset.load(
        .commonMetadata,
        .availableChapterLocales,
        .availableMetadataFormats,
      ) else { return }

      guard !Task.isCancelled, pendingAsset == asset else { return }

      if !commonMetadata.isEmpty {
        delegate?.mediaLoaderDidReceiveCommonMetadata(commonMetadata)
      }

      if !chapterLocales.isEmpty {
        for locale in chapterLocales {
          guard !Task.isCancelled else { return }
          if let chapters = try? await pendingAsset.loadChapterMetadataGroups(
            withTitleLocale: locale,
            containingItemsWithCommonKeys: [],
          ) {
            delegate?.mediaLoaderDidReceiveChapterMetadata(chapters)
          }
        }
      } else {
        let duration = await (try? pendingAsset.load(.duration)) ?? .zero
        for format in metadataFormats {
          guard !Task.isCancelled else { return }
          if let metadata = try? await pendingAsset.loadMetadata(for: format) {
            let timeRange = CMTimeRange(
              start: CMTime(seconds: 0, preferredTimescale: 1000),
              end: duration,
            )
            let group = AVTimedMetadataGroup(items: metadata, timeRange: timeRange)
            delegate?.mediaLoaderDidReceiveTimedMetadata([group])
          }
        }
      }
    }

    playableLoadTask = Task { [weak self] in
      guard let self else { return }

      do {
        let isPlayable = try await pendingAsset.load(.isPlayable)

        guard !Task.isCancelled else { return }

        await MainActor.run {
          guard pendingAsset == self.asset else { return }

          if !isPlayable {
            self.delegate?.mediaLoaderDidFailWithUnplayableTrack()
            return
          }

          let avItem = AVPlayerItem(asset: pendingAsset)
          avItem.preferredForwardBufferDuration = self.bufferDuration / 1000.0
          self.delegate?.mediaLoaderDidPrepareItem(avItem)
        }
      } catch {
        guard !Task.isCancelled else { return }

        await MainActor.run {
          guard pendingAsset == self.asset else { return }
          self.delegate?.mediaLoaderDidFailWithRetryableError(error)
        }
      }
    }
  }

  func cancelAll() {
    mediaResolverTask?.cancel()
    mediaResolverTask = nil
    metadataLoadTask?.cancel()
    metadataLoadTask = nil
    playableLoadTask?.cancel()
    playableLoadTask = nil
  }

  /// Preserves `url` and `urlOptions` so that `loadAsset()` can recreate the asset
  /// (used by `reload()` → `loadAVPlayer()` without a preceding `resolveAndLoad`).
  func clearAsset() {
    guard let currentAsset = asset else { return }

    // Don't call currentAsset.cancelLoading() on main thread - it blocks for 500ms+
    DispatchQueue.global(qos: .utility).async {
      currentAsset.cancelLoading()
    }

    asset = nil
  }

  // MARK: - Private

  private func loadWithResolvedUrl(_ resolved: MediaResolvedUrl) {
    guard let mediaUrl = URL(string: resolved.url) else {
      logger.error("Invalid media URL: \(resolved.url)")
      delegate?.mediaLoaderDidFailWithError(.invalidSourceUrl(resolved.url))
      return
    }

    var options: [String: Any] = [:]
    if let headers = resolved.headers, !headers.isEmpty {
      options["AVURLAssetHTTPHeaderFieldsKey"] = headers
    }

    let isLocalFile = mediaUrl.isFileURL
    url = isLocalFile ? URL(fileURLWithPath: mediaUrl.path) : mediaUrl
    urlOptions = options.isEmpty ? nil : options

    logger.debug("  final playbackUrl: \(mediaUrl.absoluteString)")
    logger.debug("  isLocalFile: \(isLocalFile)")

    loadAsset()
  }
}
