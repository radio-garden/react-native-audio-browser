import Foundation

/// Artwork loads whose retries ran out, kept to be loaded again by `retry()`.
@MainActor
final class FailedArtworkLoads {
  private struct Load {
    weak var owner: AnyObject?
    let reload: @MainActor () -> Void
  }

  private var loads: [Load] = []

  /// Keeps `reload` for the next `retry()` while `owner`, the row the image is for, lives; a
  /// failure that is not retryable is dropped.
  func add(_ failure: ArtworkLoadFailure, owner: AnyObject, reload: @escaping @MainActor () -> Void) {
    guard failure.isRetryable else { return }
    loads.append(Load(owner: owner, reload: reload))
  }

  /// Runs, once, each kept reload whose row still lives.
  func retry() {
    let pending = loads
    loads = []
    for load in pending where load.owner != nil {
      load.reload()
    }
  }
}
