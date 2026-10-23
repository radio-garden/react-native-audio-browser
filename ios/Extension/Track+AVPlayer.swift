import AVFoundation
import Foundation
import NitroModules

/// Extension to make Nitro Track work with AVPlayer
extension Track {
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
