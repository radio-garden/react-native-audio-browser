import Testing

@testable import AudioBrowserTestable

@Suite("buildAssetOptions")
struct BuildAssetOptionsTests {
  private func headerFields(_ options: [String: Any]?) -> [String: String]? {
    options?["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String]
  }

  @Test func userAgentOnly_setsUserAgentHeader() {
    let options = MediaLoader.buildAssetOptions(headers: nil, userAgent: "TestAgent/1.0")
    #expect(headerFields(options)?["User-Agent"] == "TestAgent/1.0")
  }

  @Test func userAgentMergedWithHeaders() {
    let options = MediaLoader.buildAssetOptions(
      headers: ["X-Test": "1"], userAgent: "TestAgent/1.0",
    )
    #expect(headerFields(options)?["X-Test"] == "1")
    #expect(headerFields(options)?["User-Agent"] == "TestAgent/1.0")
  }

  @Test func explicitUserAgentHeaderWins() {
    let options = MediaLoader.buildAssetOptions(
      headers: ["User-Agent": "Explicit"], userAgent: "TestAgent/1.0",
    )
    #expect(headerFields(options)?["User-Agent"] == "Explicit")
  }

  @Test func headersOnly_nilUserAgent_keepsHeaders() {
    let options = MediaLoader.buildAssetOptions(headers: ["X-Test": "1"], userAgent: nil)
    #expect(headerFields(options)?["X-Test"] == "1")
    #expect(headerFields(options)?["User-Agent"] == nil)
  }

  @Test func emptyUserAgent_notApplied() {
    let options = MediaLoader.buildAssetOptions(headers: nil, userAgent: "")
    #expect(options == nil)
  }

  @Test func nilUserAgentNilHeaders_returnsNil() {
    let options = MediaLoader.buildAssetOptions(headers: nil, userAgent: nil)
    #expect(options == nil)
  }
}
