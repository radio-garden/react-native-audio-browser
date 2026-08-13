import Kingfisher
import NitroModules
import os.log
import UIKit

/// Single entry point for loading artwork images from URLs via Kingfisher.
enum ArtworkImageFetcher {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "ArtworkImageFetcher")

  @MainActor
  static func fetchImage(
    from source: ImageSource,
    svgScale: CGFloat? = nil,
    downsampleTo: (size: CGSize, scale: CGFloat)? = nil,
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

    do {
      let result = try await KingfisherManager.shared.retrieveImage(with: url, options: options)
      return result.image
    } catch {
      logger.error("Failed to load artwork from \(source.uri): \(error.localizedDescription)")
      return nil
    }
  }
}
