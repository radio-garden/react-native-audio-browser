import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("BrowserError")
struct BrowserErrorTests {
  @Test func aPathNoRouteMatchesIsContentNotFoundWithoutABaseUrl() {
    guard case let .contentNotFound(path) = BrowserError.missingBaseUrl(path: "/nowhere", routed: false) else {
      Issue.record("expected contentNotFound")
      return
    }
    #expect(path == "/nowhere")
  }

  @Test func aRoutesRequestIsInvalidConfigurationWithoutABaseUrl() {
    guard case .invalidConfiguration = BrowserError.missingBaseUrl(path: "/library", routed: true) else {
      Issue.record("expected invalidConfiguration")
      return
    }
  }

  private struct JSFailure: LocalizedError {
    var errorDescription: String? { "Simulated thrown error" }
  }

  @Test func aFailingCallbackBecomesACallbackErrorWithItsMessage() async {
    do {
      _ = try await BrowserError.fromCallback { () async throws -> Int in throw JSFailure() }
      Issue.record("fromCallback returned")
    } catch let BrowserError.callbackError(message) {
      #expect(message == "Simulated thrown error")
    } catch {
      Issue.record("threw \(error)")
    }
  }

  @Test func aRejectedCallbackReachesTheAppAsCallbackError() async {
    do {
      _ = try await BrowserError.fromCallback { () async throws -> Int in throw JSPromiseRejection(JSFailure()) }
      Issue.record("fromCallback returned")
    } catch {
      let navigationError = NavigationError.from(error)
      #expect(navigationError.code == .callbackError)
      #expect(navigationError.message == "Simulated thrown error")
    }
  }

  @Test func aCallbacksBrowseErrorKeepsItsMessage() async {
    do {
      _ = try await BrowserError.fromCallback { () async throws -> Int in throw BrowserError.callbackError("subscribe first") }
      Issue.record("fromCallback returned")
    } catch let BrowserError.callbackError(message) {
      #expect(message == "subscribe first")
    } catch {
      Issue.record("threw \(error)")
    }
  }

  @Test func aCancelledCallbackStaysACancellation() async {
    await #expect(throws: CancellationError.self) {
      _ = try await BrowserError.fromCallback { () async throws -> Int in throw CancellationError() }
    }
  }
}
