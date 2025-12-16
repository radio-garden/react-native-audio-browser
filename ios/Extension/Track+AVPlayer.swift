import AVFoundation
import Foundation
import Kingfisher
import NitroModules
import UIKit

/// Extension to make Nitro Track work with AVPlayer
extension Track {
  /// The URL for AVPlayer playback (uses src for playback URL)
  var playbackUrl: URL? {
    guard let src else { return nil }
    return URL(string: src)
  }

  /// Whether this is a local file URL
  var isLocalFile: Bool {
    playbackUrl?.isFileURL ?? false
  }

  /// The source type (file or stream) based on the URL scheme
  var sourceType: SourceType {
    isLocalFile ? .file : .stream
  }

  /// Loads artwork from the artwork URL (local file or remote URL)
  /// Uses Kingfisher for caching and efficient image loading
  func loadArtwork() async -> UIImage? {
    // Try artworkSource first (has more detailed config), then fall back to artwork string
    let artworkUrlString: String?
    let artworkHeaders: [String: String]?

    if let source = artworkSource {
      artworkUrlString = source.uri
      artworkHeaders = source.headers
    } else {
      artworkUrlString = artwork
      artworkHeaders = nil
    }

    guard let urlString = artworkUrlString, let url = URL(string: urlString) else {
      return nil
    }

    if url.isFileURL {
      return UIImage(contentsOfFile: url.path)
    }

    // Build Kingfisher options with headers if provided
    var options: KingfisherOptionsInfo = []
    if let headers = artworkHeaders, !headers.isEmpty {
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
      return nil
    }
  }
}
