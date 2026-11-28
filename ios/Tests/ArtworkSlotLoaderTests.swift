import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("ArtworkSlotLoader")
struct ArtworkSlotLoaderTests {
  private static let serverError = ArtworkLoadFailure(status: 503)

  /// Answers each half's attempts in turn, repeating the last, and records what was applied.
  @MainActor
  private final class Slot {
    var outcomes: [ArtworkHalf: [Result<String, ArtworkLoadFailure>?]]
    var attempts: [ArtworkHalf: Int] = [:]
    var applied: [String] = []

    init(_ outcomes: [ArtworkHalf: [Result<String, ArtworkLoadFailure>?]]) { self.outcomes = outcomes }

    func attempt(_ half: ArtworkHalf) -> Result<String, ArtworkLoadFailure>? {
      attempts[half, default: 0] += 1
      var queue = outcomes[half]!
      let next = queue.count > 1 ? queue.removeFirst() : queue[0]
      outcomes[half] = queue
      return next
    }

    func load(pair: Bool, waits: ArtworkRetryWaits? = nil) async -> Bool {
      await ArtworkSlotLoader.load(
        pair: pair,
        waits: waits,
        attempt: { self.attempt($0) },
        combine: { "\($0)+\($1)" },
        apply: { self.applied.append($0) },
      )
    }
  }

  private static let retrying = ArtworkRetryWaits(
    wanted: { true }, sleep: { _ in }, online: { false }, shown: { throw CancellationError() },
  )

  @Test func anImageIsAppliedOnce() async {
    let slot = Slot([.only: [.success("image")]])
    #expect(await slot.load(pair: false))
    #expect(slot.applied == ["image"])
  }

  @Test func nothingToLoadAppliesNothing() async {
    let slot = Slot([.only: [nil]])
    #expect(await slot.load(pair: false) == false)
    #expect(slot.applied.isEmpty)
    #expect(slot.attempts[.only] == 1)
  }

  @Test func aFailedLoadAppliesNothing() async {
    let slot = Slot([.only: [.failure(Self.serverError)]])
    #expect(await slot.load(pair: false) == false)
    #expect(slot.applied.isEmpty)
  }

  @Test func eachRetryRunsTheWholeAttempt() async {
    let slot = Slot([.only: [.failure(Self.serverError), .failure(Self.serverError), .success("image")]])
    #expect(await slot.load(pair: false, waits: Self.retrying))
    #expect(slot.attempts[.only] == 3)
    #expect(slot.applied == ["image"])
  }

  @Test func aPairThatLoadsIsAppliedOnceCombined() async {
    let slot = Slot([.light: [.success("light")], .dark: [.success("dark")]])
    #expect(await slot.load(pair: true))
    #expect(slot.applied == ["light+dark"])
  }

  @Test func aHalfIsAppliedAloneOnceTheOtherFailed() async {
    let slot = Slot([.light: [.success("light")], .dark: [.failure(Self.serverError)]])
    #expect(await slot.load(pair: true))
    #expect(slot.applied == ["light"])
  }

  @Test func aHalfThatLoadsOnARetryJoinsTheOther() async {
    // Light loads only once dark has failed, whichever half the executor starts first.
    var applied: [String] = []
    var darkFailed: CheckedContinuation<Void, Never>?
    var darkHasFailed = false
    var darkAttempts = 0
    let loaded = await ArtworkSlotLoader.load(
      pair: true,
      waits: Self.retrying,
      attempt: { half in
        switch half {
        case .light:
          if !darkHasFailed { await withCheckedContinuation { darkFailed = $0 } }
          return .success("light")
        default:
          darkAttempts += 1
          guard darkAttempts > 1 else {
            darkHasFailed = true
            darkFailed?.resume()
            return .failure(Self.serverError)
          }
          for _ in 0 ..< 10 {
            await Task.yield()
          }
          return .success("dark")
        }
      },
      combine: { "\($0)+\($1)" },
      apply: { applied.append($0) },
    )
    #expect(loaded)
    #expect(applied == ["light", "light+dark"])
  }

  @Test func aHalfWithNothingToLoadLetsTheOtherShowAlone() async {
    let slot = Slot([.light: [nil], .dark: [.success("dark")]])
    #expect(await slot.load(pair: true))
    #expect(slot.applied == ["dark"])
  }

  @Test func aLoadCancelledBeforeItsImageArrivesAppliesNothing() async {
    var applied: [String] = []
    var release: CheckedContinuation<Void, Never>?
    let loading = Task { @MainActor in
      await ArtworkSlotLoader.load(
        pair: false,
        waits: nil,
        attempt: { _ in
          await withCheckedContinuation { release = $0 }
          return .success("image")
        },
        combine: { $1 },
        apply: { applied.append($0) },
      )
    }
    while release == nil {
      await Task.yield()
    }
    loading.cancel()
    release?.resume()
    #expect(await loading.value == false)
    #expect(applied.isEmpty)
  }
}
