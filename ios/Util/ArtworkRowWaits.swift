import Foundation

/// What wakes a row's retrying artwork load: the network changing, a template's sections being
/// replaced, or a screen being shown.
enum ArtworkWake<Screen> {
  case networkChanged
  case sectionsChanged
  case shown(Screen)
}

enum ArtworkRowWaits {
  /// The waits a failed artwork load for `row` retries on. The image is wanted while the row
  /// lives and until a template that listed it no longer does; a row not yet listed (a template
  /// still being pushed) is wanted. A wait for the network or a visit gives up at the first wake
  /// after that.
  /// - Parameter isListed: Whether a template the car holds lists the row.
  /// - Parameter shows: Whether a shown screen lists the row.
  @MainActor
  static func make<Screen>(
    for row: AnyObject,
    wakeups: Wakeups<ArtworkWake<Screen>>,
    isOnline: @escaping @MainActor () -> Bool,
    isListed: @escaping @MainActor (AnyObject) -> Bool,
    shows: @escaping @MainActor (Screen, AnyObject) -> Bool,
    sleep: @escaping @MainActor (_ ms: Int) async throws -> Void = { try await Task.sleep(for: .milliseconds($0)) },
  ) -> ArtworkRetryWaits {
    let presence = Presence(row: row, isListed: isListed)
    return ArtworkRetryWaits(
      wanted: { presence.wanted },
      sleep: sleep,
      online: {
        guard !isOnline() else { return false }
        try await wakeups.wait { _ in
          guard presence.wanted else { return .giveUp }
          return isOnline() ? .resume : .keepWaiting
        }
        return true
      },
      shown: {
        try await wakeups.wait { wake in
          guard presence.wanted, let row = presence.row else { return .giveUp }
          guard case let .shown(screen) = wake else { return .keepWaiting }
          return shows(screen, row) ? .resume : .keepWaiting
        }
      },
    )
  }
}

/// Whether a row is still wanted: alive, and listed or not yet listed.
@MainActor
private final class Presence {
  weak var row: AnyObject?
  private let isListed: @MainActor (AnyObject) -> Bool
  private var wasListed = false

  init(row: AnyObject, isListed: @escaping @MainActor (AnyObject) -> Bool) {
    self.row = row
    self.isListed = isListed
    wasListed = isListed(row)
  }

  var wanted: Bool {
    guard let row else { return false }
    let listed = isListed(row)
    wasListed = wasListed || listed
    return listed || !wasListed
  }
}
