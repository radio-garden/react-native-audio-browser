import AVFoundation
import Foundation
import NitroModules
import UIKit

/// Extension to make Nitro Track work with AVPlayer
extension Track {
  /// The URL for AVPlayer playback (uses src for playback URL)
  var playbackUrl: URL? {
    guard let src = src else { return nil }
    return URL(string: src)
  }

  /// Whether this is a local file URL
  var isLocalFile: Bool {
    playbackUrl?.isFileURL ?? false
  }

  /// The source type (file or stream) based on the URL scheme
  var sourceType: SourceType {
    return isLocalFile ? .file : .stream
  }

  /// Asset options for AVURLAsset (headers from artworkSource if available)
  var assetOptions: [String: Any]? {
    // TODO: If we need custom headers for media playback, extract from artworkSource
    // or add a dedicated field for media headers
    return nil
  }

  /// Loads artwork from the artwork URL (local file or remote URL)
  func loadArtwork(_ handler: @escaping (UIImage?) -> Void) {
    // Try artworkSource first (has more detailed config), then fall back to artwork string
    let artworkUrlString: String?
    if let source = artworkSource {
      artworkUrlString = source.uri
    } else {
      artworkUrlString = artwork
    }

    guard let urlString = artworkUrlString, let url = URL(string: urlString) else {
      handler(nil)
      return
    }

    if url.isFileURL {
      // Load from local file
      let image = UIImage(contentsOfFile: url.path)
      handler(image)
    } else {
      // Load from remote URL
      // TODO: Support custom headers from artworkSource if needed
      URLSession.shared.dataTask(with: url) { data, _, error in
        if let data = data, let image = UIImage(data: data), error == nil {
          DispatchQueue.main.async {
            handler(image)
          }
        } else {
          DispatchQueue.main.async {
            handler(nil)
          }
        }
      }.resume()
    }
  }
}
