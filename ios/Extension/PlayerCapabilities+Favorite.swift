import Foundation

extension PlayerCapabilities {
  /// Resolved favorite match mode, or `nil` when favoriting is disabled.
  ///
  /// `false`/unset → `nil`; `true` → `.exact`; `{ match }` → that match.
  var favoriteMatch: FavoritesMatchMode? {
    switch favorite {
    case .none:
      return nil
    case .some(.first(let enabled)):
      return enabled ? .exact : nil
    case .some(.second(let config)):
      return config.match
    }
  }

  /// Whether the favorite/like control is enabled.
  var favoriteEnabled: Bool { favoriteMatch != nil }
}
