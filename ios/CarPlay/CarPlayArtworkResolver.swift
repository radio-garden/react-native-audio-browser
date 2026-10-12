import Foundation

/// An artwork URL and the headers to fetch it with.
struct ArtworkResolvedImage: Equatable, Sendable {
  let uri: String
  let headers: [String: String]?
}

/// How `CarPlayImageLoader` loads one artwork.
enum ArtworkLoadAction: Equatable, Sendable {
  /// Render the `sf:` artwork at this canvas size.
  case sfSymbol(artwork: String, width: Double, height: Double)
  case fetch(uri: String, headers: [String: String]?, shouldTint: Bool, isSvg: Bool)
  case none
}

/// Decides how CarPlay loads a track's artwork, given what the Browser layer's
/// `resolveArtworkUrl` returned for it.
enum CarPlayArtworkResolver {
  /// Whether to ask the Browser layer at all: CarPlay renders an SF Symbol itself.
  static func needsResolve(artwork: String?) -> Bool {
    !isSFSymbol(artwork)
  }

  /// An SF Symbol renders; otherwise the resolved URL is fetched, falling back to the track's
  /// `artwork`, then its `artworkSource` with that source's headers. `stencil` tints the image
  /// to the surface's appearance.
  static func action(
    artwork: String?,
    artworkSource: ArtworkResolvedImage?,
    resolved: ArtworkResolvedImage?,
    stencil: Bool,
    width: Double,
    height: Double,
  ) -> ArtworkLoadAction {
    if isSFSymbol(artwork), let artwork {
      return .sfSymbol(artwork: artwork, width: width, height: height)
    }
    if let resolved, let action = fetch(resolved, stencil: stencil) {
      return action
    }
    let direct = artwork.map { ArtworkResolvedImage(uri: $0, headers: nil) } ?? artworkSource
    guard let direct else { return .none }
    if isSFSymbol(direct.uri) {
      return .sfSymbol(artwork: direct.uri, width: width, height: height)
    }
    return fetch(direct, stencil: stencil) ?? .none
  }

  private static func fetch(_ image: ArtworkResolvedImage, stencil: Bool) -> ArtworkLoadAction? {
    guard let url = URL(string: image.uri) else { return nil }
    return .fetch(
      uri: image.uri, headers: image.headers, shouldTint: stencil, isSvg: url.pathExtension.lowercased() == "svg",
    )
  }

  private static func isSFSymbol(_ artwork: String?) -> Bool {
    artwork?.hasPrefix("sf:") == true
  }
}
