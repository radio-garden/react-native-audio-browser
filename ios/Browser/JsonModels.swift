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

/// A style declaration block on the wire (ADR 0011):
/// `{ display?, gridWrap?, artworkRendering? }`.
///
/// One JSON model serves both the `TrackStyle` and `SectionStyle` positions —
/// keys that don't exist at the narrower position simply don't map.
///
/// Decoding is tolerant by design: a stale payload — the retired string
/// styles, a wrong-typed field, an unknown enum value — must degrade to "no
/// declaration", never fail the page or the persisted playback state (the
/// decoders above this are strict, so a throw here would kill the whole
/// payload). `init(from:)` therefore never throws, and unknown enum values
/// map to nil at `toNitro` time.
struct JsonStyle: Codable, Equatable {
  let display: String?
  let gridWrap: Bool?
  let artworkRendering: String?
  let imageShape: String?
  let accessorySymbol: String?

  init(
    display: String? = nil,
    gridWrap: Bool? = nil,
    artworkRendering: String? = nil,
    imageShape: String? = nil,
    accessorySymbol: String? = nil,
  ) {
    self.display = display
    self.gridWrap = gridWrap
    self.artworkRendering = artworkRendering
    self.imageShape = imageShape
    self.accessorySymbol = accessorySymbol
  }

  init(from decoder: Decoder) throws {
    // A non-object value (the retired `style: 'grid'` string) decodes as an
    // empty block rather than failing the parent.
    guard let container = try? decoder.container(keyedBy: CodingKeys.self) else {
      display = nil
      gridWrap = nil
      artworkRendering = nil
      imageShape = nil
      accessorySymbol = nil
      return
    }
    display = (try? container.decodeIfPresent(String.self, forKey: .display)) ?? nil
    gridWrap = (try? container.decodeIfPresent(Bool.self, forKey: .gridWrap)) ?? nil
    artworkRendering = (try? container.decodeIfPresent(String.self, forKey: .artworkRendering)) ?? nil
    imageShape = (try? container.decodeIfPresent(String.self, forKey: .imageShape)) ?? nil
    accessorySymbol = (try? container.decodeIfPresent(String.self, forKey: .accessorySymbol)) ?? nil
  }
}

extension JsonStyle {
  /// The wire strings map to Nitro enums here, tolerantly: an unknown value
  /// (a future `display` mode, say) is no declaration, not an error. A block
  /// that resolves to no declarations at all — `{}`, the tolerant decoder's
  /// empty result for the retired string vocabulary, or all-unknown values —
  /// maps to nil, so "no declaration" has one shape on every platform
  /// (Android's lenient mapper makes the same collapse).
  func toTrackStyle() -> TrackStyle? {
    let display = display.flatMap { StyleDisplay(fromString: $0.lowercased()) }
    let artworkRendering = artworkRendering.flatMap { ArtworkRendering(fromString: $0.lowercased()) }
    let imageShape = imageShape.flatMap { ImageShape(fromString: $0.lowercased()) }
    // The 'none' sentinel is a legitimate value; only emptiness collapses.
    let accessorySymbol = accessorySymbol?.isEmpty == false ? accessorySymbol : nil
    guard display != nil || artworkRendering != nil || imageShape != nil
      || accessorySymbol != nil
    else { return nil }
    return TrackStyle(
      display: display,
      artworkRendering: artworkRendering,
      imageShape: imageShape,
      accessorySymbol: accessorySymbol,
    )
  }

  func toSectionStyle() -> SectionStyle? {
    let display = display.flatMap { StyleDisplay(fromString: $0.lowercased()) }
    let artworkRendering = artworkRendering.flatMap { ArtworkRendering(fromString: $0.lowercased()) }
    let imageShape = imageShape.flatMap { ImageShape(fromString: $0.lowercased()) }
    let accessorySymbol = accessorySymbol?.isEmpty == false ? accessorySymbol : nil
    guard display != nil || artworkRendering != nil || gridWrap != nil
      || imageShape != nil || accessorySymbol != nil
    else { return nil }
    // Argument order is the generated init's (own properties before
    // inherited ones — Nitro flattens `extends` own-props-first).
    return SectionStyle(
      gridWrap: gridWrap,
      display: display,
      artworkRendering: artworkRendering,
      imageShape: imageShape,
      accessorySymbol: accessorySymbol,
    )
  }

  /// Snapshot a live track block back to its JSON model (for persistence).
  /// An all-nil block snapshots as no block — the decoders collapse it to
  /// "no declaration" anyway, so never persist the distinction.
  init?(_ style: TrackStyle?) {
    guard let style,
          style.display != nil || style.artworkRendering != nil
          || style.imageShape != nil || style.accessorySymbol != nil
    else { return nil }
    self.init(
      display: Self.string(style.display),
      artworkRendering: Self.string(style.artworkRendering),
      imageShape: Self.string(style.imageShape),
      accessorySymbol: style.accessorySymbol,
    )
  }

  private static func string(_ display: StyleDisplay?) -> String? {
    switch display {
    case .list: "list"
    case .grid: "grid"
    case nil: nil
    }
  }

  private static func string(_ rendering: ArtworkRendering?) -> String? {
    switch rendering {
    case .original: "original"
    case .stencil: "stencil"
    case nil: nil
    }
  }

  private static func string(_ shape: ImageShape?) -> String? {
    switch shape {
    case .circular: "circular"
    case .roundedRectangle: "rounded-rectangle"
    case nil: nil
    }
  }
}

/// JSON model for a page section (ADR 0010). Legacy `groupTitle`/`imageRow`
/// keys in payloads are simply unknown to these models and decode as
/// ignored dead weight.
struct JsonSection: Codable {
  let title: String?
  let subtitle: String?
  let style: JsonStyle?
  let path: String?
  let children: [JsonTrack]

  init(
    title: String? = nil,
    subtitle: String? = nil,
    style: JsonStyle? = nil,
    path: String? = nil,
    children: [JsonTrack],
  ) {
    self.title = title
    self.subtitle = subtitle
    self.style = style
    self.path = path
    self.children = children
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
  let sections: [JsonSection]?
  let children: [JsonTrack]?
  let src: String?
  let style: JsonStyle?
  let disabled: Bool?
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
    sections: [JsonSection]? = nil,
    children: [JsonTrack]? = nil,
    src: String? = nil,
    style: JsonStyle? = nil,
    disabled: Bool? = nil,
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
    self.sections = sections
    self.children = children
    self.src = src
    self.style = style
    self.disabled = disabled
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
  let style: JsonStyle?
  let disabled: Bool?
  let live: Bool?

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
    style: JsonStyle? = nil,
    disabled: Bool? = nil,
    live: Bool? = nil,
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
    self.disabled = disabled
    self.live = live
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
    /// The track's own declared style block and `disabled` persist (ADR 0011);
    /// section/page-inherited values are never stamped onto tracks and
    /// re-resolve at render, exactly as they do live.
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
        style: JsonStyle(track.style),
        disabled: track.disabled,
        live: track.live,
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
        style: JsonStyle(track.style),
        disabled: track.disabled,
        live: track.live,
      )
    }
  }

#endif

// MARK: - Convert JSON models to Nitro types

#if canImport(NitroModules)

  extension JsonSection {
    func toNitro() -> Section {
      Section(
        title: title,
        subtitle: subtitle,
        style: style?.toSectionStyle(),
        path: path,
        children: children.map { $0.toNitro() },
      )
    }
  }

  extension JsonResolvedTrack {
    func toNitro() -> ResolvedTrack {
      ResolvedTrack(
        path: path,
        style: style?.toSectionStyle(),
        sections: sections?.map { $0.toNitro() },
        children: children?.map { $0.toNitro() },
        carPlaySiriListButton: carPlaySiriListButton.flatMap { CarPlaySiriListButtonPosition(fromString: $0) },
        id: id,
        src: src,
        artwork: artwork?.toNitro(),
        artworkSource: nil, request: nil,
        title: title,
        subtitle: subtitle,
        artist: artist,
        albumPath: albumPath,
        album: album,
        description: description,
        genre: genre,
        duration: duration,
        disabled: disabled,
        favorited: nil,
        live: live,
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
        title: title,
        subtitle: subtitle,
        artist: artist,
        albumPath: albumPath,
        album: album,
        description: description,
        genre: genre,
        duration: duration,
        style: style?.toTrackStyle(),
        disabled: disabled,
        favorited: nil,
        live: live,
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
        artwork: artwork?.toNitro(),
        request: request.map { TrackRequest(userAgent: $0.userAgent, headers: $0.headers, query: $0.query) },
        title: title,
        artist: artist,
        albumPath: albumPath,
        album: album,
        style: style?.toTrackStyle(),
        disabled: disabled,
        live: live,
      )
    }
  }

  extension JsonSection {
    func toNitro() -> Section {
      Section(
        title: title,
        subtitle: subtitle,
        style: style?.toSectionStyle(),
        path: path,
        children: children.map { $0.toNitro() },
      )
    }
  }

  extension JsonResolvedTrack {
    func toNitro() -> ResolvedTrack {
      ResolvedTrack(
        path: path,
        style: style?.toSectionStyle(),
        sections: sections?.map { $0.toNitro() },
        children: children?.map { $0.toNitro() },
        artwork: artwork?.toNitro(),
        title: title,
        disabled: disabled,
      )
    }
  }

#endif
