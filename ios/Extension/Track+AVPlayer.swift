import AVFoundation
import Foundation
import NitroModules

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

  /// Builds an `ImageSource` from the track's artwork fields, skipping SF Symbol strings.
  var artworkImageSource: ImageSource? {
    if let source = artworkSource {
      source
    } else if let artwork = artwork?.url, !SFSymbolRenderer.isSFSymbol(artwork) {
      ImageSource(uri: artwork, method: nil, headers: nil, body: nil)
    } else {
      nil
    }
  }
}
