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
    // A non-object value (the retired `style: 'grid'` string) decodes as an
    // empty block rather than failing the parent.
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
    let cardTint = cardTint?.isEmpty == false ? cardTint : nil
    let cardImage = cardImage.flatMap { CardImage(fromString: $0.lowercased()) }
    guard display != nil || artworkRendering != nil || imageShape != nil
      || accessorySymbol != nil || cardTint != nil || cardImage != nil
    else { return nil }
    return TrackStyle(
      display: display,
      artworkRendering: artworkRendering,
      imageShape: imageShape,
      accessorySymbol: accessorySymbol,
      cardTint: cardTint,
      cardImage: cardImage,
    )
  }

  func toSectionStyle() -> SectionStyle? {
    let display = display.flatMap { StyleDisplay(fromString: $0.lowercased()) }
    let gridTile = gridTile.flatMap { GridTile(fromString: $0.lowercased()) }
    let artworkRendering = artworkRendering.flatMap { ArtworkRendering(fromString: $0.lowercased()) }
    let imageShape = imageShape.flatMap { ImageShape(fromString: $0.lowercased()) }
    let accessorySymbol = accessorySymbol?.isEmpty == false ? accessorySymbol : nil
    let cardTint = cardTint?.isEmpty == false ? cardTint : nil
    let cardImage = cardImage.flatMap { CardImage(fromString: $0.lowercased()) }
    guard display != nil || artworkRendering != nil || gridWrap != nil
      || gridTile != nil || imageShape != nil || accessorySymbol != nil
      || cardTint != nil || cardImage != nil
    else { return nil }
    // Argument order is the generated init's (own properties before
    // inherited ones — Nitro flattens `extends` own-props-first).
    return SectionStyle(
      gridWrap: gridWrap,
      gridTile: gridTile,
      display: display,
      artworkRendering: artworkRendering,
      imageShape: imageShape,
      accessorySymbol: accessorySymbol,
      cardTint: cardTint,
      cardImage: cardImage,
    )
  }

  /// Snapshot a live track block back to its JSON model (for persistence).
  /// An all-nil block snapshots as no block — the decoders collapse it to
  /// "no declaration" anyway, so never persist the distinction.
  init?(_ style: TrackStyle?) {
    guard let style,
          style.display != nil || style.artworkRendering != nil
          || style.imageShape != nil || style.accessorySymbol != nil
          || style.cardTint != nil || style.cardImage != nil
    else { return nil }
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

/// JSON model for a page section (ADR 0010). Legacy `groupTitle`/`imageRow`
/// keys in payloads are simply unknown to these models and decode as
/// ignored dead weight.
struct JsonSection: Codable {
  var title: String? = nil
  var style: JsonStyle? = nil
  var path: String? = nil
  var children: [JsonTrack]
}

/// JSON model for per-track HTTP request configuration.
struct JsonTrackRequest: Codable {
  let userAgent: String?
  let headers: [String: String]?
  let query: [String: String]?

  func toNitro() -> TrackRequest {
    TrackRequest(userAgent: userAgent, headers: headers, query: query)
  }
}

/// JSON model for resolved track (container with children).
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

/// JSON model for individual tracks.
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
