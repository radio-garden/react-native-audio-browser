import Foundation

struct ArtworkResolvedImage: Equatable, Sendable {
  let uri: String
  let headers: [String: String]?
}

enum ArtworkLoadAction: Equatable, Sendable {
  /// `width` and `height` are the canvas size in points.
  case sfSymbol(artwork: String, width: Double, height: Double)
  case fetch(uri: String, headers: [String: String]?, shouldTint: Bool)
  case none
}

enum CarPlayArtworkResolver {
  /// False for local artwork (`sf:`, `asset:`), which is never fetched.
  static func needsResolve(artwork: String?) -> Bool {
    LocalArtwork(artwork) == nil
  }

  /// An `sf:` symbol renders locally, and an `asset:` image gives none: only tabs draw it.
  /// Otherwise the resolved URL is fetched; if it is missing or does not parse, `artwork` is
  /// used, or `artworkSource` with its headers when `artwork` is nil. A direct URI that does not
  /// parse yields `.none`. `stencil` tints the fetched image to the appearance.
  static func action(
    artwork: String?,
    artworkSource: ArtworkResolvedImage?,
    resolved: ArtworkResolvedImage?,
    stencil: Bool,
    width: Double,
    height: Double,
  ) -> ArtworkLoadAction {
    if let artwork, let action = local(artwork, width: width, height: height) {
      return action
    }
    if let resolved, let action = fetch(resolved, stencil: stencil) {
      return action
    }
    let direct = artwork.map { ArtworkResolvedImage(uri: $0, headers: nil) } ?? artworkSource
    guard let direct else { return .none }
    return local(direct.uri, width: width, height: height) ?? fetch(direct, stencil: stencil) ?? .none
  }

  private static func local(_ artwork: String, width: Double, height: Double) -> ArtworkLoadAction? {
    switch LocalArtwork(artwork) {
    case .sfSymbol: .sfSymbol(artwork: artwork, width: width, height: height)
    case .asset: ArtworkLoadAction.none
    case nil: nil
    }
  }

  private static func fetch(_ image: ArtworkResolvedImage, stencil: Bool) -> ArtworkLoadAction? {
    guard URL(string: image.uri) != nil else { return nil }
    return .fetch(uri: image.uri, headers: image.headers, shouldTint: stencil)
  }
}
