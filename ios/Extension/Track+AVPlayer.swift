import AVFoundation
import Foundation
import NitroModules

extension Track {
  /// `artworkSource` if set, else `artwork.url`; nil when that is local artwork (`sf:`, `asset:`)
  /// rather than an image.
  var artworkImageSource: ImageSource? {
    if let source = artworkSource {
      source
    } else if let artwork = artwork?.url, LocalArtwork(artwork) == nil {
      ImageSource(uri: artwork, method: nil, headers: nil, body: nil)
    } else {
      nil
    }
  }
}
