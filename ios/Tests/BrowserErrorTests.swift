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
}
