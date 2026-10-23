import Kingfisher
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
    if let svgScale {
      options.append(.processor(SVGProcessor(size: nil, scale: svgScale)))
    } else if let downsampleTo {
      // Downsample rasters at decode time. Any size hint passed upstream (the
      // resolver's ImageContext) is advisory — a CDN can ignore it and return
      // the original — so this is the only sizing that is guaranteed to
      // happen. The processed image is cached under a size-qualified key;
      // cacheOriginalImage keeps the untouched original in the disk cache so
      // the same URL at another target size re-processes instead of
      // re-downloading.
      options.append(.processor(DownsamplingImageProcessor(size: downsampleTo.size)))
      options.append(.scaleFactor(downsampleTo.scale))
      options.append(.cacheOriginalImage)
    }

    if waitsForConnectivity {
      options.append(.retryStrategy(ConnectivityRetry()))
    }

    do {
      let result = try await KingfisherManager.shared.retrieveImage(with: url, options: options)
      return result.image
    } catch {
      logger.error("Failed to load artwork from \(source.uri): \(error.localizedDescription)")
      return nil
    }
  }
}

/// Repeats a fetch that failed for want of a connection once one is back, a few times at most; any
/// other failure, a 404 included, stops at once.
private struct ConnectivityRetry: RetryStrategy {
  private static let maxRetries = 3
  private let reconnect = NetworkRetryStrategy(timeoutInterval: nil)

  func retry(context: RetryContext, retryHandler: @escaping @Sendable (RetryDecision) -> Void) {
    guard context.retriedCount < Self.maxRetries,
          case let .responseError(reason: .URLSessionError(error)) = context.error,
          (error as? URLError)?.isConnectivity == true
    else {
      retryHandler(.stop)
      return
    }
    reconnect.retry(context: context, retryHandler: retryHandler)
  }
}
