import Foundation
import NitroModules

// MARK: - ImageRowItem → Track Conversion

extension ImageRowItem {
  /// Creates a minimal Track from an ImageRowItem for reuse with item selection and artwork loading.
  func toTrack() -> Track {
    Track(
        id: nil, url: url, src: nil, artwork: artwork, artworkSource: artworkSource, request: nil,
      artworkCarPlayTinted: nil, title: title, subtitle: nil, artist: nil,
      album: nil, description: nil, genre: nil, duration: nil, style: nil,
      childrenStyle: nil, favorited: nil, groupTitle: nil, live: nil, imageRow: nil
    )
  }
}
