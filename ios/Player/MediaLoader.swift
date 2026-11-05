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

  /// Resolves a src into a URL and headers; gets the `Track` so it can apply the consumer's
  /// `media.resolve(track)`.
  private let resolveUrl: (String, Track?) async -> MediaResolvedUrl

  init(resolveUrl: @escaping (String, Track?) async -> MediaResolvedUrl) {
    self.resolveUrl = resolveUrl
  }

  private(set) var asset: AVURLAsset?

  /// Milliseconds, applied as the item's `preferredForwardBufferDuration`.
  var bufferDuration: Double = 0

  enum Event {
    case prepared(AVPlayerItem)
    /// The retry loop decides whether another attempt could help.
    case failed(Error)
    /// The asset loaded but cannot play.
    case unplayable
    /// The resolved src is not a URL, so no asset exists.
    case invalidSource(String)
    case commonMetadata([AVMetadataItem])
    case chapterMetadata([AVTimedMetadataGroup])
    case timedMetadata([AVTimedMetadataGroup])
  }

  var onEvent: (@MainActor (Event) -> Void)?

  /// Readable so tests can await the resolve-then-load chain; only this type starts and
  /// cancels it.
  private(set) var mediaResolverTask: Task<Void, Never>?
  private var metadataLoadTask: Task<Void, Never>?
  private var playableLoadTask: Task<Void, Never>?

  // MARK: - Public API

  /// Resolves `src` and loads the result, reporting through `onEvent`. Cancels a resolve in
  /// flight; the previous asset's loads stay current, and can still report, until the new
  /// resolve loads an asset (an invalid source leaves them current).
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

    // Apart from the playable load, so playback need not wait for metadata.
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
        // Without chapters, each format's metadata is one group spanning the whole asset.
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

  /// Whether `candidate` is still the asset this loader loads; check it after every await.
  /// Cancelling the task cannot abort a load inside AVFoundation (only `cancelLoading()` does,
  /// and a track change does not call it while the old asset backs the playing item), so a late
  /// result would otherwise land on the next track.
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

    // `cancelLoading()` blocks for 500 ms or more: keep it off the main thread.
    DispatchQueue.global(qos: .utility).async {
      currentAsset.cancelLoading()
    }

    asset = nil
  }

  /// AVURLAsset options carrying `headers`, plus `userAgent` as `User-Agent` unless `headers`
  /// sets one (as the artwork path in RequestPipeline does). Nil when there is nothing to send,
  /// so AVPlayer keeps its default User-Agent.
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
