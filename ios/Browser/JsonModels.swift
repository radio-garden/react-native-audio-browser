import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// JSON serializable models for parsing API responses.
/// These will be converted to Nitro types after parsing.

/// A track's `artwork` on the wire: a URL string, or `{ light, dark }`.
///
/// These models are the browse-response format as well as the persistence
/// format, so this is what decides whether a payload parses at all — a plain
/// `String?` here rejects the whole page with a type mismatch, however tolerant
/// the layers above it are.
///
/// Encodes back to whichever shape it came from, so a persisted snapshot
/// round-trips, and one written before pairs existed still decodes as `.single`.
enum JsonArtwork: Codable, Equatable {
  case single(String)
  case variants(light: String, dark: String)

  private struct Variants: Codable, Equatable {
    let light: String
    let dark: String
  }

  init(from decoder: Decoder) throws {
    let container = try decoder.singleValueContainer()
    if let url = try? container.decode(String.self) {
      self = .single(url)
    } else {
      let variants = try container.decode(Variants.self)
      self = .variants(light: variants.light, dark: variants.dark)
    }
  }

  func encode(to encoder: Encoder) throws {
    var container = encoder.singleValueContainer()
    switch self {
    case let .single(url): try container.encode(url)
    case let .variants(light, dark): try container.encode(Variants(light: light, dark: dark))
    }
  }
}

extension JsonArtwork {
  /// The Nitro union this maps onto, one case per case.
  func toNitro() -> Variant_String_ArtworkVariants {
    switch self {
    case let .single(url): .first(url)
    case let .variants(light, dark): .second(ArtworkVariants(light: light, dark: dark))
    }
  }

  /// Snapshot a live artwork value back to its JSON model.
  init?(_ artwork: Variant_String_ArtworkVariants?) {
    switch artwork {
    case nil: return nil
    case let .first(url): self = .single(url)
    case let .second(variants): self = .variants(light: variants.light, dark: variants.dark)
    }
  }
}

/// JSON model for image row items (horizontal thumbnail row).
struct JsonImageRowItem: Codable {
  let id: String?
  let path: String?
  let src: String?
  let artwork: String?
  let title: String
  let artist: String?
  let album: String?
  let albumPath: String?
  let live: Bool?
  let request: JsonTrackRequest?

  init(
    id: String? = nil,
    path: String? = nil,
    src: String? = nil,
    artwork: String? = nil,
    title: String,
    artist: String? = nil,
    album: String? = nil,
    albumPath: String? = nil,
    live: Bool? = nil,
    request: JsonTrackRequest? = nil,
  ) {
    self.id = id
    self.path = path
    self.src = src
    self.artwork = artwork
    self.title = title
    self.artist = artist
    self.album = album
    self.albumPath = albumPath
    self.live = live
    self.request = request
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
  let path: String
  let title: String
  let subtitle: String?
  let artwork: JsonArtwork?
  let artist: String?
  let albumPath: String?
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
    path: String,
    title: String,
    subtitle: String? = nil,
    artwork: JsonArtwork? = nil,
    artist: String? = nil,
    albumPath: String? = nil,
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
    self.path = path
    self.title = title
    self.subtitle = subtitle
    self.artwork = artwork
    self.artist = artist
    self.albumPath = albumPath
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
  let path: String?
  let title: String
  let subtitle: String?
  let artwork: JsonArtwork?
  let artist: String?
  let albumPath: String?
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
    path: String? = nil,
    title: String,
    subtitle: String? = nil,
    artwork: JsonArtwork? = nil,
    artist: String? = nil,
    albumPath: String? = nil,
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
    self.path = path
    self.title = title
    self.subtitle = subtitle
    self.artwork = artwork
    self.artist = artist
    self.albumPath = albumPath
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

// MARK: - Snapshot live Nitro types back to JSON models (for persistence)

#if canImport(NitroModules)

  extension JsonTrackRequest {
    /// Snapshot a live TrackRequest back to its JSON model.
    init(from request: TrackRequest) {
      self.init(userAgent: request.userAgent, headers: request.headers, query: request.query)
    }
  }

  extension JsonTrack {
    /// Snapshot the persistable subset of a live Track (inverse of `toNitro()`).
    init(from track: Track) {
      self.init(
        id: track.id,
        path: track.path,
        title: track.title,
        subtitle: track.subtitle,
        artwork: JsonArtwork(track.artwork),
        artist: track.artist,
        albumPath: track.albumPath,
        album: track.album,
        description: track.description,
        genre: track.genre,
        duration: track.duration,
        src: track.src,
        request: track.request.map(JsonTrackRequest.init(from:)),
        style: nil,
        childrenStyle: nil,
        groupTitle: track.groupTitle,
        live: track.live,
        imageRow: nil,
      )
    }
  }

#else

  extension JsonTrackRequest {
    /// Snapshot a live TrackRequest back to its JSON model (SPM test-target stub).
    init(from request: TrackRequest) {
      self.init(userAgent: request.userAgent, headers: request.headers, query: request.query)
    }
  }

  extension JsonTrack {
    /// Snapshot the persistable subset of a live Track (inverse of `toNitro()`).
    /// SPM test-target stub — only fields present on the minimal `Track` stub.
    init(from track: Track) {
      self.init(
        id: track.id,
        path: track.path,
        title: track.title,
        artwork: JsonArtwork(track.artwork),
        artist: track.artist,
        albumPath: track.albumPath,
        album: track.album,
        src: track.src,
        request: track.request.map(JsonTrackRequest.init(from:)),
        live: track.live,
      )
    }
  }

#endif

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
        id: id,
        path: path,
        src: src,
        artwork: artwork,
        artworkSource: nil,
        title: title,
        artist: artist,
        album: album,
        albumPath: albumPath,
        live: live,
        request: request?.toNitro(),
      )
    }
  }

  extension JsonResolvedTrack {
    func toNitro() -> ResolvedTrack {
      ResolvedTrack(
        path: path,
        children: children?.map { $0.toNitro() },
        carPlaySiriListButton: carPlaySiriListButton.flatMap { CarPlaySiriListButtonPosition(fromString: $0) },
        id: id,
        src: src,
        artwork: artwork?.toNitro(),
        artworkSource: nil, request: nil,
        artworkCarPlayTinted: nil,
        title: title,
        subtitle: subtitle,
        artist: artist,
        albumPath: albumPath,
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
        path: path,
        src: src,
        artwork: artwork?.toNitro(),
        artworkSource: nil,
        request: request?.toNitro(),
        artworkCarPlayTinted: nil,
        title: title,
        subtitle: subtitle,
        artist: artist,
        albumPath: albumPath,
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
        path: path,
        src: src,
        request: request.map { TrackRequest(userAgent: $0.userAgent, headers: $0.headers, query: $0.query) },
        title: title,
        artist: artist,
        albumPath: albumPath,
        album: album,
        live: live,
        artwork: artwork?.toNitro(),
      )
    }
  }

  extension JsonResolvedTrack {
    func toNitro() -> ResolvedTrack {
      ResolvedTrack(
        path: path,
        children: children?.map { $0.toNitro() },
        artwork: artwork?.toNitro(),
        title: title,
      )
    }
  }

#endif
