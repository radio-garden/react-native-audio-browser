import AVFoundation
import Foundation
import NitroModules

extension Track {
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
