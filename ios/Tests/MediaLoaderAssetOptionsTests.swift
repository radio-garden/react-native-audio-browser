import Testing

@testable import AudioBrowserTestable

@Suite("buildAssetOptions")
struct BuildAssetOptionsTests {
  private func headerFields(_ options: [String: Any]?) -> [String: String]? {
    options?["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String]
  }

  @Test func userAgentOnly_setsUserAgentHeader() {
    let options = MediaLoader.buildAssetOptions(headers: nil, userAgent: "RadioGarden/1.0-1")
    #expect(headerFields(options)?["User-Agent"] == "RadioGarden/1.0-1")
  }

  @Test func userAgentMergedWithHeaders() {
    let options = MediaLoader.buildAssetOptions(
      headers: ["X-Test": "1"], userAgent: "RadioGarden/1.0-1")
    #expect(headerFields(options)?["X-Test"] == "1")
    #expect(headerFields(options)?["User-Agent"] == "RadioGarden/1.0-1")
  }

  @Test func explicitUserAgentHeaderWins() {
    let options = MediaLoader.buildAssetOptions(
      headers: ["User-Agent": "Explicit"], userAgent: "RadioGarden/1.0-1")
    #expect(headerFields(options)?["User-Agent"] == "Explicit")
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
