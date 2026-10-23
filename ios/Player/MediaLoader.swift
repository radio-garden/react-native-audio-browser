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
  private let resolveUrl: (String, Track?) async -> MediaResolvedUrl

  init(resolveUrl: @escaping (String, Track?) async -> MediaResolvedUrl) {
    self.resolveUrl = resolveUrl
  }

  /// Internal access so AVPlayerEngine can read it.
  private(set) var asset: AVURLAsset?

  /// Milliseconds; applied to AVPlayerItem's preferredForwardBufferDuration.
  var bufferDuration: Double = 0

  enum Event {
    case prepared(AVPlayerItem)
    /// Loading failed; the retry loop decides whether another attempt could help.
    case failed(Error)
    /// The asset loaded but cannot play.
    case unplayable
    /// The source is not a URL, so no asset exists.
    case invalidSource(String)
    case commonMetadata([AVMetadataItem])
    case chapterMetadata([AVTimedMetadataGroup])
    case timedMetadata([AVTimedMetadataGroup])
  }

  var onEvent: (@MainActor (Event) -> Void)?

  /// The in-flight resolve. Readable so tests can await the resolve-then-load
  /// chain instead of sleeping past it; only this type starts and cancels it.
  private(set) var mediaResolverTask: Task<Void, Never>?
  private var metadataLoadTask: Task<Void, Never>?
  private var playableLoadTask: Task<Void, Never>?

  // MARK: - Public API

  func resolveAndLoad(src: String, track: Track? = nil) {
    mediaResolverTask?.cancel()
    // `self` stays weak across the await: a consumer resolver that never settles must not pin
    // the loader, and cancellation cannot force its `await` to return.
    let resolveUrl = resolveUrl
    mediaResolverTask = Task { [weak self] in
      guard !Task.isCancelled else { return }
      let resolved = await resolveUrl(src, track)
      guard !Task.isCancelled, let self else { return }
      loadWithResolvedUrl(resolved)
    }
  }

  private func loadAsset(url: URL, options: [String: Any]?) {
    let pendingAsset = AVURLAsset(url: url, options: options)
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
        onEvent?(.commonMetadata(commonMetadata))
      }

      if !chapterLocales.isEmpty {
        for locale in chapterLocales {
          guard isCurrent(pendingAsset) else { return }
          if let chapters = try? await pendingAsset.loadChapterMetadataGroups(
            withTitleLocale: locale,
            containingItemsWithCommonKeys: [],
          ) {
            guard isCurrent(pendingAsset) else { return }
            onEvent?(.chapterMetadata(chapters))
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
            onEvent?(.timedMetadata([group]))
          }
        }
      }
    }

    playableLoadTask = Task { [weak self] in
      guard let self else { return }

      do {
        let isPlayable = try await pendingAsset.load(.isPlayable)
        guard isCurrent(pendingAsset) else { return }
        guard isPlayable else {
          onEvent?(.unplayable)
          return
        }
        let avItem = AVPlayerItem(asset: pendingAsset)
        avItem.preferredForwardBufferDuration = bufferDuration / 1000.0
        onEvent?(.prepared(avItem))
      } catch {
        guard isCurrent(pendingAsset) else { return }
        onEvent?(.failed(error))
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
  /// path in RequestPipeline). A nil/empty userAgent contributes
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
      onEvent?(.invalidSource(resolved.url))
      return
    }

    loadAsset(
      url: mediaUrl.isFileURL ? URL(fileURLWithPath: mediaUrl.path) : mediaUrl,
      options: Self.buildAssetOptions(headers: resolved.headers, userAgent: resolved.userAgent),
    )
  }
}
