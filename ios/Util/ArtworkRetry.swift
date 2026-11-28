import Foundation

/// Repeats a failed artwork load after a growing wait, so a slot left empty by a transient
/// failure fills in.
enum ArtworkRetry {
  static let delaysMs = [2_000, 4_000, 8_000, 16_000, 30_000]

  /// `attempt`'s result, retried after each of `delaysMs` while its failure is retryable and
  /// `wanted` holds. A wait that throws (a cancelled task) ends the retries.
  @MainActor
  static func load<Value>(
    wanted: @MainActor () -> Bool,
    sleep: (Int) async throws -> Void = { try await Task.sleep(for: .milliseconds($0)) },
    attempt: () async -> Result<Value, ArtworkLoadFailure>,
  ) async -> Result<Value, ArtworkLoadFailure> {
    var result = await attempt()
    for delayMs in delaysMs {
      guard case let .failure(failure) = result, failure.isRetryable, wanted() else { break }
      guard await (try? sleep(delayMs)) != nil, wanted() else { break }
      result = await attempt()
    }
    return result
  }
}
