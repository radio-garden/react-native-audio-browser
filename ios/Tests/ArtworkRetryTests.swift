import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("ArtworkRetry")
struct ArtworkRetryTests {
  private static let serverError = ArtworkLoadFailure(URLError(.badServerResponse))
  private static let notFound = ArtworkLoadFailure(status: 404)

  /// Answers each attempt in turn from `outcomes`, repeating the last, and records every attempt
  /// and wait.
  private final class Script {
    var outcomes: [Result<String, ArtworkLoadFailure>]
    var attempts = 0
    var sleptMs: [Int] = []

    init(_ outcomes: [Result<String, ArtworkLoadFailure>]) { self.outcomes = outcomes }

    func attempt() -> Result<String, ArtworkLoadFailure> {
      attempts += 1
      return outcomes.count > 1 ? outcomes.removeFirst() : outcomes[0]
    }
  }

  private func run(_ script: Script, wanted: @escaping @MainActor () -> Bool = { true }) async -> Result<String, ArtworkLoadFailure> {
    await ArtworkRetry.load(wanted: wanted, sleep: { script.sleptMs.append($0) }) { script.attempt() }
  }

  @Test func aLoadThatSucceedsIsNotRepeated() async throws {
    let script = Script([.success("image")])
    #expect(try await run(script).get() == "image")
    #expect(script.attempts == 1)
    #expect(script.sleptMs.isEmpty)
  }

  @Test func aFailedLoadIsRetriedUntilItSucceeds() async throws {
    let script = Script([.failure(Self.serverError), .failure(Self.serverError), .success("image")])
    #expect(try await run(script).get() == "image")
    #expect(script.attempts == 3)
    #expect(script.sleptMs == Array(ArtworkRetry.delaysMs.prefix(2)))
  }

  @Test func theWaitsGrowAndAreBounded() {
    #expect(zip(ArtworkRetry.delaysMs, ArtworkRetry.delaysMs.dropFirst()).allSatisfy { $0 < $1 })
    #expect(ArtworkRetry.delaysMs.reduce(0, +) <= 60_000)
  }

  @Test func anImageNotWantedIsLoadedOnceWithoutWaiting() async {
    let script = Script([.failure(Self.serverError)])
    _ = await run(script, wanted: { false })
    #expect(script.attempts == 1)
    #expect(script.sleptMs.isEmpty)
  }

  @Test func retriesStopOnceTheScheduleIsSpent() async {
    let script = Script([.failure(Self.serverError)])
    let result = await run(script)
    #expect(throws: ArtworkLoadFailure.self) { try result.get() }
    #expect(script.attempts == ArtworkRetry.delaysMs.count + 1)
    #expect(script.sleptMs == ArtworkRetry.delaysMs)
  }

  @Test func aFinalFailureIsNotRetried() async {
    let script = Script([.failure(Self.notFound)])
    let result = await run(script)
    #expect(throws: ArtworkLoadFailure.self) { try result.get() }
    #expect(script.attempts == 1)
  }

  @Test func retriesStopOnceTheImageIsNoLongerWanted() async {
    var wanted = true
    let script = Script([.failure(Self.serverError)])
    let result = await ArtworkRetry.load(wanted: { wanted }, sleep: { script.sleptMs.append($0); wanted = false }) {
      script.attempt()
    }
    #expect(throws: ArtworkLoadFailure.self) { try result.get() }
    #expect(script.attempts == 1)
  }

  @Test func aCancelledWaitEndsTheRetries() async {
    let script = Script([.failure(Self.serverError)])
    let result = await ArtworkRetry.load(wanted: { true }, sleep: { _ in throw CancellationError() }) { script.attempt() }
    #expect(throws: ArtworkLoadFailure.self) { try result.get() }
    #expect(script.attempts == 1)
  }
}
