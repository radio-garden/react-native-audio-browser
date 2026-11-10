import Foundation
import Testing

@testable import AudioBrowserTestable

/// One step of a case in spec/retry-cases.json: an event at a time, and what `SilenceBudget` and
/// `ReconnectPacer` answer after it.
private struct RetryStep: Decodable {
  enum Event: String, Decodable {
    case load, intentUp, intentDown, audioStarts, audioStops, offline, online, ended
  }

  enum Delivered: String, Decodable { case audio, nothing }

  let at: Int
  let event: Event
  let delivered: Delivered?
  let startedAt: Int?
  let deadline: Int?
  let wait: ReconnectWait?

  private static let keys: Set = ["at", "event", "delivered", "startedAt", "deadline", "wait"]

  /// Strict, so a misspelt or missing key fails the case instead of dropping an assertion.
  init(from decoder: Decoder) throws {
    let c = try strictContainer(decoder, keys: Self.keys)
    func fail(_ message: String) -> DecodingError {
      .dataCorrupted(.init(codingPath: c.codingPath, debugDescription: message))
    }
    at = try c.decode(Int.self, forKey: Key(stringValue: "at"))
    event = try c.decode(Event.self, forKey: Key(stringValue: "event"))
    delivered = try c.decodeIfPresent(Delivered.self, forKey: Key(stringValue: "delivered"))
    startedAt = try c.decodeIfPresent(Int.self, forKey: Key(stringValue: "startedAt"))
    let deadlineKey = Key(stringValue: "deadline"), waitKey = Key(stringValue: "wait")
    guard c.contains(deadlineKey) else { throw fail("at \(at): every step states its deadline") }
    deadline = try c.decodeNil(forKey: deadlineKey) ? nil : c.decode(Int.self, forKey: deadlineKey)
    if !c.contains(waitKey) {
      wait = nil
    } else if let ms = try? c.decode(Int.self, forKey: waitKey) {
      wait = .after(ms: ms)
    } else {
      wait = switch try c.decode(String.self, forKey: waitKey) {
      case "online": .untilOnline
      case "giveUp": .giveUp
      case let other: throw fail("at \(at): unknown wait \(other)")
      }
    }
    if event == .ended, delivered == nil || startedAt == nil || wait == nil {
      throw fail("at \(at): an end states what it delivered, when it started and the wait")
    }
    if let startedAt, startedAt > at { throw fail("at \(at): started after it ended") }
  }
}

private struct Key: CodingKey {
  let stringValue: String
  init(stringValue: String) { self.stringValue = stringValue }
  var intValue: Int? { nil }
  init?(intValue _: Int) { nil }
}

/// Fails on a key outside `keys`, so a misspelt one is not silently ignored.
private func strictContainer(_ decoder: Decoder, keys: Set<String>) throws -> KeyedDecodingContainer<Key> {
  let c = try decoder.container(keyedBy: Key.self)
  if let unknown = c.allKeys.first(where: { !keys.contains($0.stringValue) }) {
    throw DecodingError.dataCorrupted(.init(codingPath: c.codingPath, debugDescription: "unknown key \(unknown.stringValue)"))
  }
  return c
}

private struct RetryCase: Decodable, CustomTestStringConvertible {
  let name: String
  let maxRetries: Int?
  let recoveryMs: Int?
  let firstConnectMs: Int?
  let steps: [RetryStep]

  var testDescription: String { name }

  init(from decoder: Decoder) throws {
    let c = try strictContainer(decoder, keys: ["name", "maxRetries", "recoveryMs", "firstConnectMs", "steps"])
    name = try c.decode(String.self, forKey: Key(stringValue: "name"))
    maxRetries = try c.decodeIfPresent(Int.self, forKey: Key(stringValue: "maxRetries"))
    recoveryMs = try c.decodeIfPresent(Int.self, forKey: Key(stringValue: "recoveryMs"))
    firstConnectMs = try c.decodeIfPresent(Int.self, forKey: Key(stringValue: "firstConnectMs"))
    steps = try c.decode([RetryStep].self, forKey: Key(stringValue: "steps"))
    if zip(steps, steps.dropFirst()).contains(where: { $0.at > $1.at }) {
      throw DecodingError.dataCorrupted(.init(codingPath: c.codingPath, debugDescription: "\(name): time runs backwards"))
    }
  }
}

private let loadedCases = Result<[RetryCase], Error> {
  let url = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .appendingPathComponent("spec/retry-cases.json")
  struct File: Decodable { let cases: [RetryCase] }
  return try JSONDecoder().decode(File.self, from: Data(contentsOf: url)).cases
}

@Suite("spec/retry-cases.json")
struct RetryCaseTests {
  @Test func theCaseFileLoads() throws {
    #expect(try !loadedCases.get().isEmpty)
  }

  @Test(arguments: (try? loadedCases.get()) ?? [])
  fileprivate func runs(_ retryCase: RetryCase) throws {
    var budget = SilenceBudget(online: true)
    if let recoveryMs = retryCase.recoveryMs { budget.recoveryMs = recoveryMs }
    if let firstConnectMs = retryCase.firstConnectMs { budget.firstConnectMs = firstConnectMs }
    var pacer = ReconnectPacer(maxRetries: retryCase.maxRetries)
    var online = true
    for step in retryCase.steps {
      switch step.event {
      case .load:
        budget.loadStarted(atMs: step.at)
        pacer.reset()
      case .intentUp:
        budget.playIntentChanged(true, atMs: step.at)
        pacer.reset()
      case .intentDown: budget.playIntentChanged(false, atMs: step.at)
      case .audioStarts: budget.audioStarted(atMs: step.at)
      case .audioStops: budget.audioStopped(atMs: step.at)
      case .offline:
        online = false
        budget.onlineChanged(false)
      case .online:
        online = true
        budget.onlineChanged(true)
        pacer.networkReturned()
      case .ended:
        let delivered = try #require(step.delivered)
        let startedAt = try #require(step.startedAt)
        budget.connectionEnded(atMs: step.at)
        pacer.connectionEnded(deliveredAudio: delivered == .audio, startedAtMs: startedAt, atMs: step.at, online: online)
      }
      #expect(budget.deadlineMs == step.deadline, "deadline at \(step.at) (\(step.event))")
      if let wait = step.wait {
        #expect(pacer.wait(atMs: step.at, online: online) == wait, "wait at \(step.at) (\(step.event))")
      }
    }
  }
}
