import Testing

@testable import AudioBrowserTestable

/// The Siri play-media intent's search under `withDeadline`, as `MediaIntentHandler` runs it. The
/// work's own cancellation guard stands in for `MediaIntentPlayback.livePlayer(of:)`, which needs
/// the bridge and is not compiled here. A search
/// answering in the same main-actor turn as the deadline is not covered: `withDeadline` settles both
/// on the main actor, cancelling before it answers nil.
@MainActor
@Suite("MediaIntentDeadline")
struct MediaIntentDeadlineTests {
  private let deadline: Duration = .milliseconds(50)
  private let found = [Track(src: "one.mp3", title: "One")]

  @MainActor
  final class Intent {
    var played: [Track] = []

    func answer(within timeout: Duration, search: @escaping @MainActor () async -> [Track]?) async -> Bool? {
      await withDeadline(timeout) { _ in
        guard let tracks = await search(), !Task.isCancelled else { return false }
        self.played = tracks
        return true
      }
    }
  }

  @Test func aSilentSearchFailsAtTheDeadlineAndPlaysNothing() async throws {
    let intent = Intent()
    let deadline = deadline
    let answer = try await withinTimeLimit(.seconds(2)) {
      await intent.answer(within: deadline) {
        try? await awaitCallback { (_: @escaping @Sendable (Result<[Track]?, Error>) -> Void) in }
      }
    }
    #expect(answer == nil)
    try await Task.sleep(for: deadline * 2)
    #expect(intent.played.isEmpty)
  }

  @Test func aSearchAnsweringAfterTheDeadlinePlaysNothing() async throws {
    let intent = Intent()
    let found = found
    let late = deadline * 2
    let answer = await intent.answer(within: deadline) {
      // Ignores cancellation, as a search whose result is already in hand does.
      await withCheckedContinuation { continuation in
        Task {
          try? await Task.sleep(for: late)
          continuation.resume()
        }
      }
      return found
    }
    #expect(answer == nil)
    try await Task.sleep(for: late * 2)
    #expect(intent.played.isEmpty)
  }

  @Test func aSearchAnsweringInTimePlays() async {
    let intent = Intent()
    let found = found
    let answer = await intent.answer(within: .seconds(5)) { found }
    #expect(answer == true)
    #expect(intent.played == found)
  }

  @Test func theWorkIsGivenTheDeadline() async {
    let before = ContinuousClock.now
    let given = await withDeadline(.seconds(5)) { $0 }
    #expect(given.map { before.duration(to: $0) >= .seconds(5) } == true)
    #expect(given.map { ContinuousClock.now.duration(to: $0) <= .seconds(5) } == true)
  }
}
