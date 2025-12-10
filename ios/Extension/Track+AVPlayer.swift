import AVFoundation
import Foundation
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
  func loadArtwork(_ handler: @escaping (UIImage?) -> Void) {
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
      handler(nil)
      return
    }

    if url.isFileURL {
      // Load from local file
      let image = UIImage(contentsOfFile: url.path)
      handler(image)
    } else {
      // Load from remote URL with optional headers
      var request = URLRequest(url: url)
      if let headers = artworkHeaders {
        for (key, value) in headers {
          request.setValue(value, forHTTPHeaderField: key)
        }
      }

      URLSession.shared.dataTask(with: request) { data, _, error in
        if let data, let image = UIImage(data: data), error == nil {
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
