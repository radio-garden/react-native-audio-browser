import Foundation
import NitroModules

// MARK: - ImageRowItem → Track Conversion

extension ImageRowItem {
  /// Creates a minimal Track from an ImageRowItem for reuse with item selection and artwork loading.
  /// A `src`-bearing item becomes a playable track (thumbnail tap plays it); otherwise the
  /// track only navigates via `url`.
  func toTrack() -> Track {
    Track(
      id: id, url: url, src: src, artwork: artwork.map { .first($0) },
      artworkSource: artworkSource, request: request,
      artworkCarPlayTinted: nil, title: title, subtitle: nil, artist: artist,
      albumUrl: albumUrl, album: album, description: nil, genre: nil, duration: nil, style: nil,
      childrenStyle: nil, favorited: nil, groupTitle: nil, live: live, imageRow: nil,
    )
  }
}
