import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// A track's `artwork` on the wire: a URL string, or `{ light, dark }`. Encodes back to the
/// shape it came from, so a persisted snapshot round-trips.
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
  func toNitro() -> Variant_String_ArtworkVariants {
    switch self {
    case let .single(url): .first(url)
    case let .variants(light, dark): .second(ArtworkVariants(light: light, dark: dark))
    }
  }

  init?(_ artwork: Variant_String_ArtworkVariants?) {
    switch artwork {
    case nil: return nil
    case let .first(url): self = .single(url)
    case let .second(variants): self = .variants(light: variants.light, dark: variants.dark)
    }
  }
}

/// A style block on the wire (ADR 0011), for both the track and the section position.
///
/// Decoding never throws: a non-object value, a wrong-typed field or an unknown enum value
/// degrades to no declaration, because the decoders above are strict and a throw here fails
/// the whole page or the persisted playback state.
struct JsonStyle: Codable, Equatable {
  var display: String? = nil
  var gridWrap: Bool? = nil
  var gridTile: String? = nil
  var artworkRendering: String? = nil
  var imageShape: String? = nil
  var accessorySymbol: String? = nil
  var cardTint: String? = nil
  var cardImage: String? = nil
}

extension JsonStyle {
  init(from decoder: Decoder) throws {
    guard let container = try? decoder.container(keyedBy: CodingKeys.self) else {
      self.init()
      return
    }
    self.init(
      display: (try? container.decodeIfPresent(String.self, forKey: .display)) ?? nil,
      gridWrap: (try? container.decodeIfPresent(Bool.self, forKey: .gridWrap)) ?? nil,
      gridTile: (try? container.decodeIfPresent(String.self, forKey: .gridTile)) ?? nil,
      artworkRendering: (try? container.decodeIfPresent(String.self, forKey: .artworkRendering)) ?? nil,
      imageShape: (try? container.decodeIfPresent(String.self, forKey: .imageShape)) ?? nil,
      accessorySymbol: (try? container.decodeIfPresent(String.self, forKey: .accessorySymbol)) ?? nil,
      cardTint: (try? container.decodeIfPresent(String.self, forKey: .cardTint)) ?? nil,
      cardImage: (try? container.decodeIfPresent(String.self, forKey: .cardImage)) ?? nil,
    )
  }
}

extension JsonStyle {
  /// Maps the wire strings case-insensitively; an unknown value or an empty string is no
  /// declaration, and a block left with none is nil, as Android's mapper does.
  func toTrackStyle() -> TrackStyle? {
    let declarations = trackDeclarations
    return Self.declaresAnything(declarations) ? declarations : nil
  }

  func toSectionStyle() -> SectionStyle? {
    let declarations = trackDeclarations
    let gridTile = gridTile.flatMap { GridTile(fromString: $0.lowercased()) }
    guard Self.declaresAnything(declarations) || gridWrap != nil || gridTile != nil else { return nil }
    return SectionStyle(
      gridWrap: gridWrap,
      gridTile: gridTile,
      display: declarations.display,
      artworkRendering: declarations.artworkRendering,
      imageShape: declarations.imageShape,
      accessorySymbol: declarations.accessorySymbol,
      cardTint: declarations.cardTint,
      cardImage: declarations.cardImage,
    )
  }

  private var trackDeclarations: TrackStyle {
    TrackStyle(
      display: display.flatMap { StyleDisplay(fromString: $0.lowercased()) },
      artworkRendering: artworkRendering.flatMap { ArtworkRendering(fromString: $0.lowercased()) },
      imageShape: imageShape.flatMap { ImageShape(fromString: $0.lowercased()) },
      // 'none' is a legitimate symbol; only an empty string collapses.
      accessorySymbol: accessorySymbol?.isEmpty == false ? accessorySymbol : nil,
      cardTint: cardTint?.isEmpty == false ? cardTint : nil,
      cardImage: cardImage.flatMap { CardImage(fromString: $0.lowercased()) },
    )
  }

  private static func declaresAnything(_ style: TrackStyle) -> Bool {
    style.display != nil || style.artworkRendering != nil || style.imageShape != nil
      || style.accessorySymbol != nil || style.cardTint != nil || style.cardImage != nil
  }

  /// The persistable form of a live block; nil when it declares nothing.
  init?(_ style: TrackStyle?) {
    guard let style, Self.declaresAnything(style) else { return nil }
    self.init(
      display: Self.string(style.display),
      artworkRendering: Self.string(style.artworkRendering),
      imageShape: Self.string(style.imageShape),
      accessorySymbol: style.accessorySymbol,
      cardTint: style.cardTint,
      cardImage: Self.string(style.cardImage),
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

  private static func string(_ mode: CardImage?) -> String? {
    switch mode {
    case .normal: "normal"
    case .background: "background"
    case nil: nil
    }
  }
}

struct JsonSection: Codable {
  var title: String? = nil
  var style: JsonStyle? = nil
  var path: String? = nil
  var children: [JsonTrack]
}

struct JsonTrackRequest: Codable {
  let userAgent: String?
  let headers: [String: String]?
  let query: [String: String]?

  func toNitro() -> TrackRequest {
    TrackRequest(userAgent: userAgent, headers: headers, query: query)
  }
}

struct JsonResolvedTrack: Codable {
  var id: String? = nil
  var path: String
  var title: String
  var subtitle: String? = nil
  var artwork: JsonArtwork? = nil
  var artist: String? = nil
  var albumPath: String? = nil
  var album: String? = nil
  var description: String? = nil
  var genre: String? = nil
  var duration: Double? = nil
  var sections: [JsonSection]? = nil
  var children: [JsonTrack]? = nil
  var src: String? = nil
  var style: JsonStyle? = nil
  var disabled: Bool? = nil
  var live: Bool? = nil
  var icyCharset: String? = nil
  var carPlaySiriListButton: String? = nil
}

struct JsonTrack: Codable {
  var id: String? = nil
  var path: String? = nil
  var title: String
  var subtitle: String? = nil
  var artwork: JsonArtwork? = nil
  var artist: String? = nil
  var albumPath: String? = nil
  var album: String? = nil
  var description: String? = nil
  var genre: String? = nil
  var duration: Double? = nil
  var src: String? = nil
  var request: JsonTrackRequest? = nil
  var style: JsonStyle? = nil
  var disabled: Bool? = nil
  var live: Bool? = nil
  var icyCharset: String? = nil
}

// MARK: - Snapshot live Nitro types back to JSON models (for persistence)

extension JsonTrackRequest {
  init(from request: TrackRequest) {
    self.init(userAgent: request.userAgent, headers: request.headers, query: request.query)
  }
}

extension JsonTrack {
  /// Snapshots the persistable subset of a live Track. Its style is the track's own block; section- and page-inherited style re-resolves at render.
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
      icyCharset: track.icyCharset,
    )
  }
}

// MARK: - Convert JSON models to Nitro types

extension JsonSection {
  func toNitro() -> Section {
    Section(
      title: title,
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
      icyCharset: icyCharset,
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
      icyCharset: icyCharset,
    )
  }
}
