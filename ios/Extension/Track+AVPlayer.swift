import AVFoundation
import Foundation
import NitroModules

extension Track {
  /// `artworkSource` if set, else `artwork.url`; nil when that names an SF Symbol rather than an image.
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
