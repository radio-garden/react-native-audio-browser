import Foundation

extension PlayerCapabilities {
  /// Resolved favorite match mode, or `nil` when favoriting is disabled.
  ///
  /// `false`/unset → `nil`; `true` → `.exact`; `{ match }` → that match.
  var favoriteMatch: FavoritesMatchMode? {
    switch favorite {
    case .none:
      nil
    case let .some(.first(enabled)):
      enabled ? .exact : nil
    case let .some(.second(config)):
      config.match
    }
  }

  /// Whether the favorite/like control is enabled.
  var favoriteEnabled: Bool { favoriteMatch != nil }
}
