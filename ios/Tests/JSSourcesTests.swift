import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("JS sources")
struct JSSourcesTests {
  /// Nitro's `RuntimeError` for a JS error: an `Error` whose message is its `localizedDescription`.
  struct JSError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
  }

  private typealias Answer = Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_

  private let param = BrowserSourceCallbackParam(path: "/a", routeParams: nil)
  private let page = ResolvedTrack(path: "/a", title: "A")

  private func callbackErrorMessage(_ body: () async throws -> some Any) async -> String? {
    do {
      _ = try await body()
      Issue.record("returned")
    } catch let BrowserError.callbackError(message) {
      return message
    } catch {
      Issue.record("threw \(error)")
    }
    return nil
  }

  @Test func aBrowseCallbacksPageComesBack() async throws {
    let resolved = try await JSSources.browse({ _ in .resolved(withResult: .third(.resolved(withResult: .first(page)))) }, param)
    #expect(resolved == page)
  }

  @Test func aBrowseCallbackWhosePromiseRejectsFailsWithItsMessage() async {
    let message = await callbackErrorMessage {
      try await JSSources.browse({ _ in .resolved(withResult: .third(.rejected(withError: JSError("Error: token expired")))) }, param)
    }
    #expect(message == "token expired")
  }

  @Test func aBrowseCallbackThatThrowsFailsWithItsMessage() async {
    let message = await callbackErrorMessage {
      try await JSSources.browse({ _ in Promise<Answer>.rejected(withError: JSError("token expired")) }, param)
    }
    #expect(message == "token expired")
  }

  @Test func aBrowseCallbacksErrorResultFailsWithItsMessage() async {
    let message = await callbackErrorMessage {
      try await JSSources.browse({ _ in .resolved(withResult: .second(BrowseError(error: "subscribe first"))) }, param)
    }
    #expect(message == "subscribe first")
  }

  @Test func aNestedSourceWhosePromiseRejectsFailsWithItsMessage() async {
    let message = await callbackErrorMessage {
      try await JSSources.nested { Promise.resolved(withResult: Promise<Int>.rejected(withError: JSError("TypeError: search is down"))) }
    }
    #expect(message == "search is down")
  }

  @Test func aNestedSourceThatThrowsFailsWithItsMessage() async {
    let message = await callbackErrorMessage {
      try await JSSources.nested { Promise<Promise<Int>>.rejected(withError: JSError("signed out")) }
    }
    #expect(message == "signed out")
  }
}
