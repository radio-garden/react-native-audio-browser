import Kingfisher
import NitroModules
import UIKit
import os.log

/// Single entry point for loading artwork images from URLs via Kingfisher.
enum ArtworkImageFetcher {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "ArtworkImageFetcher")

  @MainActor
  static func fetchImage(from source: ImageSource, svgScale: CGFloat? = nil) async -> UIImage? {
    guard let url = URL(string: source.uri) else { return nil }

    var options: KingfisherOptionsInfo = []
    if let headers = source.headers, !headers.isEmpty {
      let modifier = AnyModifier { request in
        var request = request
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        return request
      }
      options.append(.requestModifier(modifier))
    }
    if let svgScale {
      options.append(.processor(SVGProcessor(size: nil, scale: svgScale)))
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
