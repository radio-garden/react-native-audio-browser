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

  /// Resolves a playback src into a concrete URL + headers/user-agent.
  /// The `Track` is threaded through so the resolver can invoke the
  /// consumer-supplied `media.resolve(track)` as the final media layer.
  var mediaUrlResolver: ((String, Track?) async -> MediaResolvedUrl)?

  /// Internal access so TrackPlayer can read it for observer guards.
  private(set) var asset: AVURLAsset?

  /// Milliseconds; applied to AVPlayerItem's preferredForwardBufferDuration.
  var bufferDuration: Double = 0

  weak var delegate: MediaLoaderDelegate?

  /// The in-flight resolve. Readable so tests can await the resolve-then-load
  /// chain instead of sleeping past it; only this type starts and cancels it.
  private(set) var mediaResolverTask: Task<Void, Never>?
  private var metadataLoadTask: Task<Void, Never>?
  private var playableLoadTask: Task<Void, Never>?
  private var url: URL?
  private var urlOptions: [String: Any]?

  // MARK: - Public API

  func resolveAndLoad(src: String, track: Track? = nil) {
    if let resolver = mediaUrlResolver {
      mediaResolverTask?.cancel()
      // `[weak self]` like the sibling load tasks below: the resolver is
      // consumer-supplied and `cancelAll()` cannot force an in-flight `await`
      // to return, so a strong capture let a resolver that never settles pin
      // this loader (which also retains the task) indefinitely.
      // `Logger` is a value type, so capturing it directly keeps the logging
      // off `self` — and `self` weak, so a consumer resolver that never settles
      // can't pin the loader (`cancelAll()` can't force an in-flight `await` to
      // return).
      let logger = logger
      mediaResolverTask = Task { [weak self] in
        logger.debug("resolveAndLoad: starting resolution for \(src)")

        guard !Task.isCancelled else {
          logger.debug("resolveAndLoad: cancelled before start")
          return
        }

        logger.debug("resolveAndLoad: calling resolver...")
        let resolved = await resolver(src, track)

        guard !Task.isCancelled, let self else {
          logger.debug("resolveAndLoad: cancelled after resolver returned")
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
    guard let url else {
      // Callers (reload-from-terminal) have already transitioned to .loading;
      // a silent return would strand that state with no load in flight.
      delegate?.mediaLoaderDidFailWithError(.invalidSourceUrl("nil"))
      return
    }
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

      guard isCurrent(pendingAsset) else { return }

      if !commonMetadata.isEmpty {
        delegate?.mediaLoaderDidReceiveCommonMetadata(commonMetadata)
      }

      if !chapterLocales.isEmpty {
        for locale in chapterLocales {
          guard isCurrent(pendingAsset) else { return }
          if let chapters = try? await pendingAsset.loadChapterMetadataGroups(
            withTitleLocale: locale,
            containingItemsWithCommonKeys: [],
          ) {
            guard isCurrent(pendingAsset) else { return }
            delegate?.mediaLoaderDidReceiveChapterMetadata(chapters)
          }
        }
      } else {
        let duration = await (try? pendingAsset.load(.duration)) ?? .zero
        for format in metadataFormats {
          guard isCurrent(pendingAsset) else { return }
          if let metadata = try? await pendingAsset.loadMetadata(for: format) {
            guard isCurrent(pendingAsset) else { return }
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

  /// Whether `candidate` is still the load this loader cares about — checked
  /// after EVERY await, not just once before a loop.
  ///
  /// Cancellation alone is not enough: it only sets a flag, and can't abort a
  /// load already in flight inside AVFoundation (only `cancelLoading()` does,
  /// and the track-change path deliberately doesn't call it — the old asset
  /// still backs the playing item at that point). So a load that lands after
  /// the track changed would otherwise attribute the old asset's metadata to
  /// the newly loaded track.
  private func isCurrent(_ candidate: AVURLAsset) -> Bool {
    !Task.isCancelled && candidate == asset
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

  /// Builds AVURLAsset options, merging any explicit headers with a resolved
  /// User-Agent. An explicit `User-Agent` in `headers` wins (mirrors the artwork
  /// path in BrowserManager+URLResolution). A nil/empty userAgent contributes
  /// nothing, so "no UA configured" still falls through to AVPlayer's default.
  nonisolated static func buildAssetOptions(
    headers: [String: String]?,
    userAgent: String?,
  ) -> [String: Any]? {
    var merged = headers ?? [:]
    if let userAgent, !userAgent.isEmpty, merged["User-Agent"] == nil {
      merged["User-Agent"] = userAgent
    }
    return merged.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": merged]
  }

  // MARK: - Private

  private func loadWithResolvedUrl(_ resolved: MediaResolvedUrl) {
    guard let mediaUrl = URL(string: resolved.url) else {
      logger.error("Invalid media URL: \(resolved.url)")
      delegate?.mediaLoaderDidFailWithError(.invalidSourceUrl(resolved.url))
      return
    }

    let isLocalFile = mediaUrl.isFileURL
    url = isLocalFile ? URL(fileURLWithPath: mediaUrl.path) : mediaUrl
    urlOptions = Self.buildAssetOptions(headers: resolved.headers, userAgent: resolved.userAgent)

    logger.debug("  final playbackUrl: \(mediaUrl.absoluteString)")
    logger.debug("  isLocalFile: \(isLocalFile)")

    loadAsset()
  }
}
