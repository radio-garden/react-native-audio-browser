import CarPlay
import Foundation
import NitroModules

// MARK: - Track CarPlay Extensions

extension Track {
  /// Returns the best artwork URL for CarPlay display.
  var carPlayArtworkUrl: URL? {
    if let artworkSource {
      let uri = artworkSource.uri
      return URL(string: uri)
    }
    if let artwork {
      return URL(string: artwork)
    }
    return nil
  }

  /// Returns whether this track is playable (has a media source).
  var isPlayable: Bool {
    src != nil
  }

  /// Returns whether this track is browsable (has a navigation URL).
  var isBrowsable: Bool {
    url != nil && src == nil
  }

  /// Returns the display subtitle for CarPlay.
  /// Uses subtitle if available, falls back to artist.
  var carPlaySubtitle: String? {
    subtitle ?? artist
  }
}

// MARK: - ImageRowItem → Track Conversion

extension ImageRowItem {
  /// Creates a minimal Track from an ImageRowItem for reuse with item selection and artwork loading.
  func toTrack() -> Track {
    Track(
      url: url, src: nil, artwork: artwork, artworkSource: artworkSource,
      artworkCarPlayTinted: nil, title: title, subtitle: nil, artist: nil,
      album: nil, description: nil, genre: nil, duration: nil, style: nil,
      childrenStyle: nil, favorited: nil, groupTitle: nil, live: nil, imageRow: nil
    )
  }
}

// MARK: - ResolvedTrack CarPlay Extensions

extension ResolvedTrack {
  /// Returns playable children only (tracks with src).
  var playableChildren: [Track] {
    children?.filter { $0.src != nil } ?? []
  }

  /// Returns browsable children only (tracks with url but no src).
  var browsableChildren: [Track] {
    children?.filter { $0.url != nil && $0.src == nil } ?? []
  }
}
