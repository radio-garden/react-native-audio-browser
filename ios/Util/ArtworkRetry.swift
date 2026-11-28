import Foundation

struct ArtworkRetryWaits {
  /// Whether the image is still wanted; checked before every wait and attempt.
  var wanted: @MainActor () -> Bool
  var sleep: @MainActor (_ ms: Int) async throws -> Void
  /// Returns false at once when online; otherwise waits for a connection and returns true.
  var online: @MainActor () async throws -> Bool
  /// Returns when the image's row is shown again.
  var shown: @MainActor () async throws -> Void
}

/// Repeats a failed artwork load, so a slot left empty by a transient failure fills in.
enum ArtworkRetry {
  static let delaysMs = [2_000, 4_000, 8_000, 16_000, 30_000]

  /// `attempt`'s result, retried while its failure is retryable and the image is wanted: at once
  /// when a connection failure finds the device offline and it comes back online, otherwise after
  /// each of `delaysMs`, and once they are spent, when the row is next shown, which starts them
  /// over. A wait that throws ends the retries; nil `waits` attempts once.
  @MainActor
  static func load<Value>(
    waits: ArtworkRetryWaits?,
    attempt: @MainActor () async -> Result<Value, ArtworkLoadFailure>,
  ) async -> Result<Value, ArtworkLoadFailure> {
    var result = await attempt()
    guard let waits else { return result }
    var delays = delaysMs[...]
    while case let .failure(failure) = result, failure.isRetryable, waits.wanted() {
      do {
        let reconnected = failure.isConnectionFailure ? try await waits.online() : false
        if !reconnected, let delayMs = delays.popFirst() {
          try await waits.sleep(delayMs)
        } else if !reconnected {
          try await waits.shown()
          delays = delaysMs[...]
        }
      } catch {
        break
      }
      guard waits.wanted() else { break }
      result = await attempt()
    }
    return result
  }
}
