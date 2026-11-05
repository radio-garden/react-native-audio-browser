import Foundation

struct ArtworkResolvedImage: Equatable, Sendable {
  let uri: String
  let headers: [String: String]?
}

enum ArtworkLoadAction: Equatable, Sendable {
  /// `width` and `height` are the canvas size in points.
  case sfSymbol(artwork: String, width: Double, height: Double)
  case fetch(uri: String, headers: [String: String]?, shouldTint: Bool, isSvg: Bool)
  case none
}

enum CarPlayArtworkResolver {
  /// False for an `sf:` symbol, which CarPlay renders itself.
  static func needsResolve(artwork: String?) -> Bool {
    !isSFSymbol(artwork)
  }

  /// An `sf:` symbol renders locally. Otherwise the resolved URL is fetched; if it is missing or does not
  /// parse, `artwork` is used, or `artworkSource` with its headers when `artwork` is nil. A direct URI
  /// that does not parse yields `.none`. `stencil` tints the fetched image to the appearance.
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
