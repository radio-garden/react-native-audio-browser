/// Artwork the app draws itself instead of fetching: an `sf:` SF Symbol, or an `asset:` image
/// from the app's asset catalog, which only CarPlay tabs draw.
enum LocalArtwork: Equatable {
  case sfSymbol
  case asset(name: String)

  /// Nil for an image URL, or no artwork.
  init?(_ artwork: String?) {
    guard let artwork else { return nil }
    if artwork.hasPrefix("sf:") {
      self = .sfSymbol
    } else if artwork.hasPrefix("asset:") {
      self = .asset(name: String(artwork.dropFirst("asset:".count)))
    } else {
      return nil
    }
  }
}
