import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("ArtworkRetry")
struct ArtworkRetryTests {
  private static let serverError = ArtworkLoadFailure(status: 503)
  private static let connectionFailure = ArtworkLoadFailure(urlErrorCode: .notConnectedToInternet)
  private static let notFound = ArtworkLoadFailure(status: 404)

  private struct Ended: Error {}

  /// Answers each attempt in turn from `outcomes`, repeating the last, and records every attempt
  /// and every wait in order.
  @MainActor
  private final class Script {
    var outcomes: [Result<String, ArtworkLoadFailure>]
    var attempts = 0
    var waits: [String] = []
    var wanted = true
    var offline = false
    /// How many visits `shown` lets through before it ends, as a row that is gone does.
    var visits = 0

    init(_ outcomes: [Result<String, ArtworkLoadFailure>]) { self.outcomes = outcomes }

    func attempt() -> Result<String, ArtworkLoadFailure> {
      attempts += 1
      return outcomes.count > 1 ? outcomes.removeFirst() : outcomes[0]
    }

    var retryWaits: ArtworkRetryWaits {
      ArtworkRetryWaits(
        wanted: { self.wanted },
        sleep: { self.waits.append("sleep \($0)") },
        online: {
          guard self.offline else { return false }
          self.waits.append("online")
          self.offline = false
          return true
        },
        shown: {
          guard self.visits > 0 else { throw Ended() }
          self.visits -= 1
          self.waits.append("shown")
        },
      )
    }

    func run() async -> Result<String, ArtworkLoadFailure> {
      await ArtworkRetry.load(waits: retryWaits) { self.attempt() }
    }
  }

  private func sleeps(_ delays: some Sequence<Int>) -> [String] {
    delays.map { "sleep \($0)" }
  }

  @Test func aLoadThatSucceedsIsNotRepeated() async throws {
    let script = Script([.success("image")])
    #expect(try await script.run().get() == "image")
    #expect(script.attempts == 1)
    #expect(script.waits.isEmpty)
  }

  @Test func withoutWaitsALoadIsMadeOnce() async {
    var attempts = 0
    let result: Result<String, ArtworkLoadFailure> = await ArtworkRetry.load(waits: nil) {
      attempts += 1
      return .failure(Self.serverError)
    }
    #expect(throws: ArtworkLoadFailure.self) { try result.get() }
    #expect(attempts == 1)
  }

  @Test func aServerErrorIsRetriedAfterEachWaitOfTheSchedule() async throws {
    let script = Script([.failure(Self.serverError), .failure(Self.serverError), .success("image")])
    #expect(try await script.run().get() == "image")
    #expect(script.attempts == 3)
    #expect(script.waits == sleeps(ArtworkRetry.delaysMs.prefix(2)))
  }

  @Test func aConnectionFailureWhileOnlineWaitsOutTheScheduleLikeAnyOther() async throws {
    let script = Script([.failure(Self.connectionFailure), .failure(Self.connectionFailure), .success("image")])
    #expect(try await script.run().get() == "image")
    #expect(script.attempts == 3)
    #expect(script.waits == sleeps(ArtworkRetry.delaysMs.prefix(2)))
  }

  @Test func aConnectionFailureWhileOfflineIsRetriedOnceTheConnectionReturns() async throws {
    let script = Script([.failure(Self.connectionFailure), .success("image")])
    script.offline = true
    #expect(try await script.run().get() == "image")
    #expect(script.attempts == 2)
    #expect(script.waits == ["online"])
  }

  @Test func aServerErrorWhileOfflineWaitsOutTheSchedule() async throws {
    let script = Script([.failure(Self.serverError), .success("image")])
    script.offline = true
    #expect(try await script.run().get() == "image")
    #expect(script.waits == sleeps(ArtworkRetry.delaysMs.prefix(1)))
  }

  @Test func reconnectingDoesNotUseUpAWaitOfTheSchedule() async throws {
    let script = Script([.failure(Self.connectionFailure), .failure(Self.serverError), .success("image")])
    script.offline = true
    #expect(try await script.run().get() == "image")
    #expect(script.waits == ["online"] + sleeps(ArtworkRetry.delaysMs.prefix(1)))
  }

  @Test func onceTheScheduleIsSpentTheNextVisitStartsItOver() async throws {
    let script = Script(
      Array(repeating: .failure(Self.serverError), count: ArtworkRetry.delaysMs.count + 2) + [.success("image")],
    )
    script.visits = 1
    #expect(try await script.run().get() == "image")
    #expect(script.waits == sleeps(ArtworkRetry.delaysMs) + ["shown"] + sleeps(ArtworkRetry.delaysMs.prefix(1)))
    #expect(script.attempts == ArtworkRetry.delaysMs.count + 3)
  }

  @Test func retriesEndWhenTheRowIsGoneBeforeItsNextVisit() async {
    let script = Script([.failure(Self.serverError)])
    let result = await script.run()
    #expect(throws: ArtworkLoadFailure.self) { try result.get() }
    #expect(script.attempts == ArtworkRetry.delaysMs.count + 1)
    #expect(script.waits == sleeps(ArtworkRetry.delaysMs))
  }

  @Test func aFinalFailureIsNotRetried() async {
    let script = Script([.failure(Self.notFound)])
    _ = await script.run()
    #expect(script.attempts == 1)
    #expect(script.waits.isEmpty)
  }

  @Test func anImageNotWantedIsLoadedOnceWithoutWaiting() async {
    let script = Script([.failure(Self.serverError)])
    script.wanted = false
    _ = await script.run()
    #expect(script.attempts == 1)
    #expect(script.waits.isEmpty)
  }

  @Test func anImageNoLongerWantedAfterAWaitIsNotLoadedAgain() async {
    let script = Script([.failure(Self.serverError)])
    var waits = script.retryWaits
    waits.sleep = { _ in script.wanted = false }
    _ = await ArtworkRetry.load(waits: waits) { script.attempt() }
    #expect(script.attempts == 1)
  }

  @Test func aWaitThatThrowsEndsTheRetries() async {
    let script = Script([.failure(Self.serverError)])
    var waits = script.retryWaits
    waits.sleep = { _ in throw CancellationError() }
    _ = await ArtworkRetry.load(waits: waits) { script.attempt() }
    #expect(script.attempts == 1)
  }

  @Test func theWaitsGrowAndAreBounded() {
    #expect(zip(ArtworkRetry.delaysMs, ArtworkRetry.delaysMs.dropFirst()).allSatisfy { $0 < $1 })
    #expect(ArtworkRetry.delaysMs.reduce(0, +) <= 60_000)
  }
}
