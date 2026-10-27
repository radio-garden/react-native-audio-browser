import Kingfisher
import Network
import NitroModules
import os.log
import UIKit

/// Single entry point for loading artwork images from URLs via Kingfisher.
enum ArtworkImageFetcher {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "ArtworkImageFetcher")

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
      // Downsample rasters at decode time. Any size hint passed upstream (the
      // resolver's ImageContext) is advisory — a CDN can ignore it and return
      // the original — so this is the only sizing that is guaranteed to
      // happen. The processed image is cached under a size-qualified key;
      // cacheOriginalImage keeps the untouched original in the disk cache so
      // the same URL at another target size re-processes instead of
      // re-downloading.
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
    // Only an HTTP fetch records a lifetime: a file: or data: image would be refetched on every show.
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

  /// Fetches a stale or never-fetched image again. When that fails, the cached copy, if any, is
  /// shown; with `retry`, a fetch that failed for want of a connection waits for one first.
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

  /// Fetches through Kingfisher, and records how long an image from the network stays fresh.
  @MainActor
  private static func retrieve(
    _ url: URL,
    options: KingfisherOptionsInfo,
    freshnessKey: String,
  ) async -> Result<UIImage, any Error> {
    do {
      let result = try await KingfisherManager.shared.retrieveImage(with: url, options: options)
      // Metrics mark a download: an image re-made from the cached original is `.none` too.
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

/// The cache keys whose image is still fresh, each kept on disk until its lifetime ends.
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

/// Repeats a fetch that failed for want of a connection once one is back, a few times at most; any
/// other failure, a 404 included, stops at once.
private struct ConnectivityRetry: RetryStrategy {
  private static let maxRetries = 3

  func retry(context: RetryContext, retryHandler: @escaping @Sendable (RetryDecision) -> Void) {
    guard context.retriedCount < Self.maxRetries, context.error.isConnectivity else {
      retryHandler(.stop)
      return
    }
    Self.whenConnected { retryHandler(.retry(userInfo: nil)) }
  }

  /// Calls `body` once the device has a usable path, at once when it has one already: a monitor
  /// reports the current path when it starts, so a reconnect cannot slip past it. The handler
  /// holds the monitor until it fires.
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

  /// Serial, so a monitor's handler never runs twice at once.
  private static let queue = DispatchQueue(label: "com.audiobrowser.artwork-connectivity", qos: .utility)
}
