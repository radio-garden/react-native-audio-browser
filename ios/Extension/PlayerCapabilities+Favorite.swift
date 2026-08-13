import Foundation

extension PlayerCapabilities {
  /// Whether the favorite/like control is enabled.
  var favoriteEnabled: Bool { favorite ?? false }
}
