import Kingfisher
import Network
import NitroModules
import os.log
import UIKit

/// Loads artwork through Kingfisher, refetching an HTTP image once its cached copy is stale.
enum ArtworkImageFetcher {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "ArtworkImageFetcher")

  /// Returns nil when the image cannot be loaded, logging a failed fetch (not an unparseable URI). A stale image whose refetch fails
  /// falls back to the cached copy. `svgScale` wins over `downsampleTo`.
  /// - Parameter waitsForConnectivity: A fetch that fails for want of a connection waits for one
  ///   and tries again. A cancelled fetch that is waiting ends when the connection returns.
  @MainActor
  static func fetchImage(
    from source: ImageSource,
    svgScale: CGFloat? = nil,
    downsampleTo: (size: CGSize, scale: CGFloat)? = nil,
    waitsForConnectivity: Bool = false,
  ) async -> UIImage? {
    guard let url = URL(string: source.uri) else { return nil }

    var options: KingfisherOptionsInfo = []
    if let headers = source.headers, !headers.isEmpty {
      let modifier = AnyModifier { request in
        var request = request
        for (key, value) in headers {
          request.setValue(value, forHTTPHeaderField: key)
        }
        return request
      }
      options.append(.requestModifier(modifier))
    }
    let processor: (any ImageProcessor)?
    if let svgScale {
      processor = SVGProcessor(size: nil, scale: svgScale)
    } else if let downsampleTo {
      // The only sizing that is guaranteed: a size hint sent upstream is advisory and a CDN may
      // return the original. cacheOriginalImage keeps the original on disk so another target
      // size re-processes it instead of downloading it again.
      processor = DownsamplingImageProcessor(size: downsampleTo.size)
      options.append(.scaleFactor(downsampleTo.scale))
      options.append(.cacheOriginalImage)
    } else {
      processor = nil
    }
    if let processor { options.append(.processor(processor)) }
    let retry: KingfisherOptionsInfo = waitsForConnectivity ? [.retryStrategy(ConnectivityRetry())] : []

    // Each processed variant is fresh on its own: a variant cached before the image changed
    // would otherwise outlive the refetch of another.
    let key = url.absoluteString + (processor?.identifier ?? "")
    // Only HTTP fetches record a lifetime, so any other scheme would otherwise refetch on every call.
    let isFresh = if ["http", "https"].contains(url.scheme?.lowercased()) { await freshness.isFresh(key) } else { true }
    let result: Result<UIImage, any Error> = if isFresh {
      await retrieve(url, options: options + retry, freshnessKey: key)
    } else {
      await refetch(url, options: options, retry: retry, freshnessKey: key)
    }
    switch result {
    case let .success(image):
      return image
    case let .failure(error):
      logger.error("Failed to load artwork from \(source.uri): \(error.localizedDescription)")
      return nil
    }
  }

  private static let freshness = FreshnessStore()

  /// Fetches past the cache. On failure returns the cached copy if there is one; otherwise, with
  /// `retry`, a connectivity failure waits for a connection and fetches again. The first fetch
  /// skips `retry` so a cached copy is shown without waiting for the network.
  @MainActor
  private static func refetch(
    _ url: URL,
    options: KingfisherOptionsInfo,
    retry: KingfisherOptionsInfo,
    freshnessKey: String,
  ) async -> Result<UIImage, any Error> {
    let fetched = await retrieve(url, options: options + [.forceRefresh], freshnessKey: freshnessKey)
    guard case let .failure(error) = fetched else { return fetched }
    if let cached = try? await KingfisherManager.shared.retrieveImage(with: url, options: options + [.onlyFromCache]) {
      return .success(cached.image)
    }
    guard !retry.isEmpty, (error as? KingfisherError)?.isConnectivity == true else { return fetched }
    return await retrieve(url, options: options + [.forceRefresh] + retry, freshnessKey: freshnessKey)
  }

  /// Fetches through Kingfisher and records the freshness lifetime of an image that was downloaded.
  @MainActor
  private static func retrieve(
    _ url: URL,
    options: KingfisherOptionsInfo,
    freshnessKey: String,
  ) async -> Result<UIImage, any Error> {
    do {
      let result = try await KingfisherManager.shared.retrieveImage(with: url, options: options)
      // `.none` also covers an image re-processed from the cached original; only metrics mark a download.
      if result.cacheType == .none, let metrics = result.metrics {
        let responses = metrics.rawMetrics.transactionMetrics.compactMap { $0.response as? HTTPURLResponse }
        await freshness.record(freshnessKey, lifetime: ArtworkFreshness.lifetime(of: responses))
      }
      return .success(result.image)
    } catch {
      return .failure(error)
    }
  }
}

/// Cache keys whose image is still fresh, each stored on disk with its lifetime as expiry.
private actor FreshnessStore {
  private let storage = try? DiskStorage.Backend<Data>(
    config: DiskStorage.Config(name: "com.audiobrowser.artwork-freshness", sizeLimit: 0),
  )
  private var sweptExpired = false

  func isFresh(_ key: String) -> Bool {
    guard let storage else { return false }
    if !sweptExpired {
      sweptExpired = true
      _ = try? storage.removeExpiredValues()
    }
    return storage.isCached(forKey: key)
  }

  func record(_ key: String, lifetime: TimeInterval) {
    guard let storage else { return }
    if lifetime > 0 {
      try? storage.store(value: Data(), forKey: key, expiration: .seconds(lifetime))
    } else {
      try? storage.remove(forKey: key)
    }
  }
}

private extension KingfisherError {
  var isConnectivity: Bool {
    guard case let .responseError(reason: .URLSessionError(error)) = self else { return false }
    return (error as? URLError)?.isConnectivity == true
  }
}

/// Retries a connectivity failure once a connection is back, up to `maxRetries` times; any other
/// failure, a 404 included, stops immediately.
private struct ConnectivityRetry: RetryStrategy {
  private static let maxRetries = 3

  func retry(context: RetryContext, retryHandler: @escaping @Sendable (RetryDecision) -> Void) {
    guard context.retriedCount < Self.maxRetries, context.error.isConnectivity else {
      retryHandler(.stop)
      return
    }
    Self.whenConnected { retryHandler(.retry(userInfo: nil)) }
  }

  /// Calls `body` once there is a usable path, immediately if there is one already: a monitor
  /// reports the current path when it starts, so a reconnect cannot be missed. The handler keeps
  /// the monitor alive until it fires.
  private static func whenConnected(_ body: @escaping @Sendable () -> Void) {
    let monitor = NWPathMonitor()
    monitor.pathUpdateHandler = { path in
      guard path.status == .satisfied, monitor.pathUpdateHandler != nil else { return }
      monitor.pathUpdateHandler = nil
      monitor.cancel()
      body()
    }
    monitor.start(queue: queue)
  }

  // Serial, so a monitor's handler never runs concurrently with itself.
  private static let queue = DispatchQueue(label: "com.audiobrowser.artwork-connectivity", qos: .utility)
}
