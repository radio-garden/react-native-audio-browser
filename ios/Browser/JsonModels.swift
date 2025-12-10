import Foundation
import NitroModules

/// JSON serializable models for parsing API responses.
/// These will be converted to Nitro types after parsing.

/// JSON model for resolved track (container with children).
struct JsonResolvedTrack: Codable {
  let url: String
  let title: String
  let subtitle: String?
  let artwork: String?
  let artist: String?
  let album: String?
  let description: String?
  let genre: String?
  let duration: Double?
  let children: [JsonTrack]?
  let src: String?
  let style: String?
  let childrenStyle: String?
  let groupTitle: String?

  init(
    url: String,
    title: String,
    subtitle: String? = nil,
    artwork: String? = nil,
    artist: String? = nil,
    album: String? = nil,
    description: String? = nil,
    genre: String? = nil,
    duration: Double? = nil,
    children: [JsonTrack]? = nil,
    src: String? = nil,
    style: String? = nil,
    childrenStyle: String? = nil,
    groupTitle: String? = nil
  ) {
    self.url = url
    self.title = title
    self.subtitle = subtitle
    self.artwork = artwork
    self.artist = artist
    self.album = album
    self.description = description
    self.genre = genre
    self.duration = duration
    self.children = children
    self.src = src
    self.style = style
    self.childrenStyle = childrenStyle
    self.groupTitle = groupTitle
  }
}

/// JSON model for individual tracks.
struct JsonTrack: Codable {
  let url: String?
  let title: String
  let subtitle: String?
  let artwork: String?
  let artist: String?
  let album: String?
  let description: String?
  let genre: String?
  let duration: Double?
  let src: String?
  let style: String?
  let childrenStyle: String?
  let groupTitle: String?

  init(
    url: String? = nil,
    title: String,
    subtitle: String? = nil,
    artwork: String? = nil,
    artist: String? = nil,
    album: String? = nil,
    description: String? = nil,
    genre: String? = nil,
    duration: Double? = nil,
    src: String? = nil,
    style: String? = nil,
    childrenStyle: String? = nil,
    groupTitle: String? = nil
  ) {
    self.url = url
    self.title = title
    self.subtitle = subtitle
    self.artwork = artwork
    self.artist = artist
    self.album = album
    self.description = description
    self.genre = genre
    self.duration = duration
    self.src = src
    self.style = style
    self.childrenStyle = childrenStyle
    self.groupTitle = groupTitle
  }
}

// MARK: - Convert JSON models to Nitro types

private extension String {
  func toTrackStyle() -> TrackStyle? {
    TrackStyle(fromString: lowercased())
  }
}

extension JsonResolvedTrack {
  func toNitro() -> ResolvedTrack {
    ResolvedTrack(
      url: url,
      children: children?.map { $0.toNitro() },
      src: src,
      artwork: artwork,
      artworkSource: nil,
      title: title,
      subtitle: subtitle,
      artist: artist,
      album: album,
      description: description,
      genre: genre,
      duration: duration,
      style: style?.toTrackStyle(),
      childrenStyle: childrenStyle?.toTrackStyle(),
      favorited: nil,
      groupTitle: groupTitle
    )
  }
}

extension JsonTrack {
  func toNitro() -> Track {
    Track(
      url: url,
      src: src,
      artwork: artwork,
      artworkSource: nil,
      title: title,
      subtitle: subtitle,
      artist: artist,
      album: album,
      description: description,
      genre: genre,
      duration: duration,
      style: style?.toTrackStyle(),
      childrenStyle: childrenStyle?.toTrackStyle(),
      favorited: nil,
      groupTitle: groupTitle
    )
  }
}
