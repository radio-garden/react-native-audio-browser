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

@Test func buildUrlEmptyPathReturnsBaseWithoutTrailingSlash() {
  // An endpoint whose baseUrl already includes the full path (e.g. search,
  // `https://host/api/search`) passes an empty path — the base IS the URL, and
  // must not gain a dangling trailing slash.
  let result = BrowserPathHelper.buildUrl(baseUrl: "http://example.com/api/search", path: "")
  #expect(result == "http://example.com/api/search")
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

// MARK: - build

@Test func buildAppendsTrackIdWithQuestionMark() {
  let result = BrowserPathHelper.build(parentPath: "/library", trackId: "song.mp3")
  #expect(result == "/library?__trackId=song.mp3")
}

@Test func buildAppendsTrackIdWithAmpersandWhenParentHasQuery() {
  let result = BrowserPathHelper.build(parentPath: "/search?q=jazz", trackId: "song.mp3")
  #expect(result == "/search?q=jazz&__trackId=song.mp3")
}

@Test func buildRoundTripsSrcWithQueryParams() {
  // A src carrying its own query string (signed CDN URL) must survive the
  // build → extract/strip round-trip: an unescaped `&` splits the src into
  // stray query params, truncating the trackId and polluting the parent path.
  let src = "https://cdn.example.com/stream.mp3?token=abc&exp=1699999999"
  let url = BrowserPathHelper.build(parentPath: "/library", trackId: src)
  #expect(BrowserPathHelper.extractTrackId(url) == src)
  #expect(BrowserPathHelper.stripTrackId(url) == "/library")
}

@Test func buildRoundTripsSrcWithEqualsAndPlus() {
  let src = "https://cdn.example.com/a+b.mp3?sig=x=y"
  let url = BrowserPathHelper.build(parentPath: "/library", trackId: src)
  #expect(BrowserPathHelper.extractTrackId(url) == src)
  #expect(BrowserPathHelper.stripTrackId(url) == "/library")
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
