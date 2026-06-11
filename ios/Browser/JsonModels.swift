import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// JSON serializable models for parsing API responses.
/// These will be converted to Nitro types after parsing.

/// JSON model for image row items (horizontal thumbnail row).
struct JsonImageRowItem: Codable {
  let url: String?
  let artwork: String?
  let title: String

  init(
    url: String? = nil,
    artwork: String? = nil,
    title: String,
  ) {
    self.url = url
    self.artwork = artwork
    self.title = title
  }
}

/// JSON model for per-track HTTP request configuration.
struct JsonTrackRequest: Codable {
  let userAgent: String?
  let headers: [String: String]?
  let query: [String: String]?

  #if canImport(NitroModules)
    func toNitro() -> TrackRequest {
      TrackRequest(userAgent: userAgent, headers: headers, query: query)
    }
  #endif
}

/// JSON model for resolved track (container with children).
struct JsonResolvedTrack: Codable {
  let id: String?
  let url: String
  let title: String
  let subtitle: String?
  let artwork: String?
  let artist: String?
  let albumUrl: String?
  let album: String?
  let description: String?
  let genre: String?
  let duration: Double?
  let children: [JsonTrack]?
  let src: String?
  let style: String?
  let childrenStyle: String?
  let groupTitle: String?
  let live: Bool?
  let carPlaySiriListButton: String?

  init(
    id: String? = nil,
    url: String,
    title: String,
    subtitle: String? = nil,
    artwork: String? = nil,
    artist: String? = nil,
    albumUrl: String? = nil,
    album: String? = nil,
    description: String? = nil,
    genre: String? = nil,
    duration: Double? = nil,
    children: [JsonTrack]? = nil,
    src: String? = nil,
    style: String? = nil,
    childrenStyle: String? = nil,
    groupTitle: String? = nil,
    live: Bool? = nil,
    carPlaySiriListButton: String? = nil,
  ) {
    self.url = url
    self.title = title
    self.subtitle = subtitle
    self.artwork = artwork
    self.artist = artist
    self.albumUrl = albumUrl
    self.album = album
    self.description = description
    self.genre = genre
    self.duration = duration
    self.children = children
    self.src = src
    self.style = style
    self.childrenStyle = childrenStyle
    self.groupTitle = groupTitle
    self.live = live
    self.carPlaySiriListButton = carPlaySiriListButton
    self.id = id
  }
}

/// JSON model for individual tracks.
struct JsonTrack: Codable {
  let id: String?
  let url: String?
  let title: String
  let subtitle: String?
  let artwork: String?
  let artist: String?
  let albumUrl: String?
  let album: String?
  let description: String?
  let genre: String?
  let duration: Double?
  let src: String?
  let request: JsonTrackRequest?
  let style: String?
  let childrenStyle: String?
  let groupTitle: String?
  let live: Bool?
  let imageRow: [JsonImageRowItem]?

  init(
    id: String? = nil,
    url: String? = nil,
    title: String,
    subtitle: String? = nil,
    artwork: String? = nil,
    artist: String? = nil,
    albumUrl: String? = nil,
    album: String? = nil,
    description: String? = nil,
    genre: String? = nil,
    duration: Double? = nil,
    src: String? = nil,
    request: JsonTrackRequest? = nil,
    style: String? = nil,
    childrenStyle: String? = nil,
    groupTitle: String? = nil,
    live: Bool? = nil,
    imageRow: [JsonImageRowItem]? = nil,
  ) {
    self.url = url
    self.title = title
    self.subtitle = subtitle
    self.artwork = artwork
    self.artist = artist
    self.albumUrl = albumUrl
    self.album = album
    self.description = description
    self.genre = genre
    self.duration = duration
    self.src = src
    self.request = request
    self.style = style
    self.childrenStyle = childrenStyle
    self.groupTitle = groupTitle
    self.live = live
    self.imageRow = imageRow
    self.id = id
  }
}

// MARK: - Convert JSON models to Nitro types

#if canImport(NitroModules)

  private extension String {
    func toTrackStyle() -> TrackStyle? {
      TrackStyle(fromString: lowercased())
    }
  }

  extension JsonImageRowItem {
    func toNitro() -> ImageRowItem {
      ImageRowItem(
        url: url,
        artwork: artwork,
        artworkSource: nil,
        title: title,
      )
    }
  }

  extension JsonResolvedTrack {
    func toNitro() -> ResolvedTrack {
      ResolvedTrack(
        url: url,
        children: children?.map { $0.toNitro() },
        carPlaySiriListButton: carPlaySiriListButton.flatMap { CarPlaySiriListButtonPosition(fromString: $0) },
        id: id,
        src: src,
        artwork: artwork,
        artworkSource: nil, request: nil,
        artworkCarPlayTinted: nil,
        title: title,
        subtitle: subtitle,
        artist: artist,
        albumUrl: albumUrl,
        album: album,
        description: description,
        genre: genre,
        duration: duration,
        style: style?.toTrackStyle(),
        childrenStyle: childrenStyle?.toTrackStyle(),
        favorited: nil,
        groupTitle: groupTitle,
        live: live,
        imageRow: nil,
      )
    }
  }

  extension JsonTrack {
    func toNitro() -> Track {
      Track(
        id: id,
        url: url,
        src: src,
        artwork: artwork,
        artworkSource: nil,
        request: request?.toNitro(),
        artworkCarPlayTinted: nil,
        title: title,
        subtitle: subtitle,
        artist: artist,
        albumUrl: albumUrl,
        album: album,
        description: description,
        genre: genre,
        duration: duration,
        style: style?.toTrackStyle(),
        childrenStyle: childrenStyle?.toTrackStyle(),
        favorited: nil,
        groupTitle: groupTitle,
        live: live,
        imageRow: imageRow?.map { $0.toNitro() },
      )
    }
  }

#else

  // Test-only path: construct minimal stubs for SPM test builds.
  extension JsonTrack {
    func toNitro() -> Track {
      Track(
        id: id ?? "",
        url: url,
        src: src,
        request: request.map { TrackRequest(userAgent: $0.userAgent, headers: $0.headers, query: $0.query) },
        title: title,
        artist: artist,
        albumUrl: albumUrl,
        album: album,
        live: live,
      )
    }
  }

#endif
