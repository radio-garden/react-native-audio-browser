import Testing

@testable import AudioBrowserTestable

// MARK: - buildUrl

@Test func buildUrlCombinesBaseAndPath() {
  let result = BrowserPathHelper.buildUrl(baseUrl: "http://example.com", path: "api/test")
  #expect(result == "http://example.com/api/test")
}

@Test func buildUrlNormalizesSlashes() {
  let result = BrowserPathHelper.buildUrl(baseUrl: "http://example.com/", path: "/api/test")
  #expect(result == "http://example.com/api/test")
}

@Test func buildUrlReturnsPathWhenNoBaseUrl() {
  let result = BrowserPathHelper.buildUrl(baseUrl: nil, path: "/api/test")
  #expect(result == "/api/test")
}

@Test func buildUrlReturnsFullUrlPathAsIs() {
  let result = BrowserPathHelper.buildUrl(baseUrl: "http://example.com", path: "https://other.com/test")
  #expect(result == "https://other.com/test")
}

@Test func buildUrlStripsMultipleTrailingSlashesFromBase() {
  let result = BrowserPathHelper.buildUrl(baseUrl: "http://example.com///", path: "api")
  #expect(result == "http://example.com/api")
}

// MARK: - appendQuery

@Test func appendQueryAddsParamsWithQuestionMark() {
  let result = BrowserPathHelper.appendQuery(["q": "jazz"], to: "/search")
  #expect(result == "/search?q=jazz")
}

@Test func appendQueryAddsParamsWithAmpersand() {
  let result = BrowserPathHelper.appendQuery(["page": "2"], to: "/items?sort=new")
  #expect(result == "/items?sort=new&page=2")
}

@Test func appendQueryReturnsUrlUnchangedForEmptyDict() {
  let result = BrowserPathHelper.appendQuery([:], to: "/search")
  #expect(result == "/search")
}

@Test func appendQueryPercentEncodesValues() {
  let result = BrowserPathHelper.appendQuery(["q": "hello world"], to: "/search")
  #expect(result == "/search?q=hello%20world")
}

@Test func appendQueryEncodesAmpersandAndEqualsInValues() {
  let result = BrowserPathHelper.appendQuery(["filter": "a=1&b=2"], to: "/items")
  #expect(result == "/items?filter=a%3D1%26b%3D2")
}

@Test func appendQueryEncodesPlusInValues() {
  let result = BrowserPathHelper.appendQuery(["q": "c++"], to: "/search")
  #expect(result == "/search?q=c%2B%2B")
}

@Test func appendQuerySortsKeysDeterministically() {
  let result = BrowserPathHelper.appendQuery(["z": "3", "a": "1", "m": "2"], to: "/items")
  #expect(result == "/items?a=1&m=2&z=3")
}

// MARK: - stripTrackId

@Test func stripTrackIdRemovesTrackParam() {
  let result = BrowserPathHelper.stripTrackId("/library/radio?__trackId=song.mp3")
  #expect(result == "/library/radio")
}

@Test func stripTrackIdPreservesOtherParams() {
  let result = BrowserPathHelper.stripTrackId("/search?q=jazz&__trackId=song.mp3")
  #expect(result == "/search?q=jazz")
}

@Test func stripTrackIdReturnsNonContextualUrlUnchanged() {
  let result = BrowserPathHelper.stripTrackId("/library/radio")
  #expect(result == "/library/radio")
}

// MARK: - extractTrackId

@Test func extractTrackIdReturnsId() {
  let result = BrowserPathHelper.extractTrackId("/library/radio?__trackId=song.mp3")
  #expect(result == "song.mp3")
}

@Test func extractTrackIdReturnsNilForNonContextual() {
  let result = BrowserPathHelper.extractTrackId("/library/radio")
  #expect(result == nil)
}

// MARK: - isContextual

@Test func isContextualReturnsTrueForContextualUrl() {
  #expect(BrowserPathHelper.isContextual("/lib?__trackId=x") == true)
}

@Test func isContextualReturnsFalseForPlainUrl() {
  #expect(BrowserPathHelper.isContextual("/lib") == false)
}

@Test func isContextualReturnsTrueWithAmpersandSeparator() {
  #expect(BrowserPathHelper.isContextual("/lib?q=1&__trackId=x") == true)
}
