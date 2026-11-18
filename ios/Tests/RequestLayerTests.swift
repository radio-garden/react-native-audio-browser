import Testing

@testable import AudioBrowserTestable

private struct Config: RequestFields, Equatable {
  var method: String?
  var path: String?
  var baseUrl: String?
  var headers: [String: String]?
  var query: [String: String]?
  var body: String?
  var contentType: String?
  var userAgent: String?

  init(
    method: String? = nil,
    path: String? = nil,
    baseUrl: String? = nil,
    headers: [String: String]? = nil,
    query: [String: String]? = nil,
    body: String? = nil,
    contentType: String? = nil,
    userAgent: String? = nil,
  ) {
    self.method = method
    self.path = path
    self.baseUrl = baseUrl
    self.headers = headers
    self.query = query
    self.body = body
    self.contentType = contentType
    self.userAgent = userAgent
  }
}

@Suite("RequestLayer")
struct RequestLayerTests {
  @Test func mergeLetsTheOverrideWinFieldByField() {
    let base = Config(method: "GET", path: "/a", baseUrl: "https://a", userAgent: "base")
    let merged = RequestLayer.merge(base, Config(path: "/b", userAgent: "over"))
    #expect(merged == Config(method: "GET", path: "/b", baseUrl: "https://a", userAgent: "over"))
  }

  @Test func mergeCombinesHeadersAndQueryByKey() {
    let base = Config(headers: ["A": "1", "B": "1"], query: ["x": "1"])
    let merged = RequestLayer.merge(base, Config(headers: ["B": "2"], query: ["y": "2"]))
    #expect(merged.headers == ["A": "1", "B": "2"])
    #expect(merged.query == ["x": "1", "y": "2"])
  }

  @Test func anEmptyOverrideChangesNothing() {
    let base = Config(method: "POST", path: "/a", baseUrl: "https://a", headers: ["A": "1"])
    #expect(RequestLayer.merge(base, Config()) == base)
  }

  @Test func applyStaticKeepsTheBasePath() {
    let base = Config(path: "/page", baseUrl: "https://a")
    let applied = RequestLayer.applyStatic(Config(path: "/ignored", baseUrl: "https://b"), to: base)
    #expect(applied == Config(path: "/page", baseUrl: "https://b"))
  }

  @Test func transformsWinCompletelyInOrder() async throws {
    let base = Config(path: "/page", baseUrl: "https://a", headers: ["A": "1"])
    let layer = Config(baseUrl: "https://static")
    let applied = try await RequestLayer.apply(
      layer,
      transforms: [
        { Config(path: $0.path, baseUrl: "https://first") },
        { Config(path: $0.path, baseUrl: $0.baseUrl, userAgent: "second") },
      ],
      to: base,
    )
    #expect(applied == Config(path: "/page", baseUrl: "https://first", userAgent: "second"))
  }

  @Test func withoutTransformsTheStaticFieldsApply() async throws {
    let base = Config(path: "/page", baseUrl: "https://a")
    let applied = try await RequestLayer.apply(Config(baseUrl: "https://b"), transforms: [], to: base)
    #expect(applied == Config(path: "/page", baseUrl: "https://b"))
  }

  @Test func aFailingTransformThrows() async {
    struct Boom: Error {}
    await #expect(throws: Boom.self) {
      _ = try await RequestLayer.apply(Config(), transforms: [{ _ in throw Boom() }], to: Config())
    }
  }

  @Test func mergeResolvedLetsSyncWin() {
    let asyncResolved = Config(baseUrl: "https://async", userAgent: "ASYNC")
    let syncResolved = Config(userAgent: "SYNC")
    #expect(RequestLayer.mergeResolved(async: Config?.none, sync: nil) == nil)
    #expect(RequestLayer.mergeResolved(async: asyncResolved, sync: nil) == asyncResolved)
    #expect(RequestLayer.mergeResolved(async: nil, sync: syncResolved) == syncResolved)
    #expect(
      RequestLayer.mergeResolved(async: asyncResolved, sync: syncResolved)
        == Config(baseUrl: "https://async", userAgent: "SYNC"),
    )
  }

  @Test func buildUrlJoinsBaseUrlPathAndQuery() {
    #expect(RequestLayer.buildUrl(Config(path: "/a", baseUrl: "https://host")) == "https://host/a")
    #expect(RequestLayer.buildUrl(Config(baseUrl: "https://host"), defaultPath: "/d") == "https://host/d")
    #expect(RequestLayer.buildUrl(Config(path: "/a", baseUrl: "https://host", query: ["q": "x"])) == "https://host/a?q=x")
  }

  @Test func mergeDictsIsNilSafe() {
    #expect(RequestLayer.mergeDicts(nil, nil) == nil)
    #expect(RequestLayer.mergeDicts(["a": "1"], nil) == ["a": "1"])
    #expect(RequestLayer.mergeDicts(nil, ["b": "2"]) == ["b": "2"])
  }

  @Test func baseSetsOnlyThePath() {
    #expect(Config.base(path: "/a") == Config(path: "/a"))
  }

  // MARK: - artwork

  private func artwork(
    _ artwork: Config = Config(),
    over base: Config = Config(path: "/art.png"),
    resolve: (nonisolated(nonsending)() async throws -> Config?)? = nil,
    trackHasArtwork: Bool = true,
    sizeQuery: [String: String] = [:],
    transforms: [nonisolated(nonsending)(Config) async throws -> Config] = [],
    id: String? = nil,
  ) async throws -> Config? {
    try await RequestLayer.artwork(
      artwork, over: base, resolve: resolve, trackHasArtwork: trackHasArtwork,
      sizeQuery: sizeQuery, transforms: transforms, id: id,
    )
  }

  @Test func artworkStaticFieldsMergeOverTheBasePathIncluded() async throws {
    let result = try await artwork(
      Config(path: "/artwork/{id}", baseUrl: "https://img", headers: ["B": "2"]),
      over: Config(path: "/art.png", baseUrl: "https://api", headers: ["A": "1"]),
      id: "42",
    )
    #expect(result == Config(path: "/artwork/42", baseUrl: "https://img", headers: ["A": "1", "B": "2"]))
  }

  @Test func theResolvedConfigWinsOverTheStaticFields() async throws {
    let result = try await artwork(
      Config(baseUrl: "https://static", userAgent: "static"),
      resolve: { Config(baseUrl: "https://resolved") },
    )
    #expect(result == Config(path: "/art.png", baseUrl: "https://resolved", userAgent: "static"))
  }

  @Test func aResolverThatProducesNothingKeepsTheTracksArtwork() async throws {
    let result = try await artwork(Config(baseUrl: "https://img"), resolve: { nil })
    #expect(result == Config(path: "/art.png", baseUrl: "https://img"))
  }

  @Test func aResolverThatProducesNothingForATrackWithoutArtworkIsNoArtwork() async throws {
    let result = try await artwork(
      Config(path: "/fallback.png"), over: Config(), resolve: { nil }, trackHasArtwork: false,
    )
    #expect(result == nil)
  }

  @Test func theSizeQueryMergesBeforeTheTransformsRun() async throws {
    var seen: Config?
    let result = try await artwork(
      over: Config(path: "/art.png", query: ["v": "1"]),
      sizeQuery: ["w": "300"],
      transforms: [{ seen = $0; return $0 }],
    )
    #expect(seen?.query == ["v": "1", "w": "300"])
    #expect(result?.query == ["v": "1", "w": "300"])
  }

  @Test func anEmptySizeQueryLeavesNoQuery() async throws {
    #expect(try await artwork()?.query == nil)
  }

  @Test func transformsRunInOrderBeforeTheIdIsFilled() async throws {
    let result = try await artwork(
      transforms: [
        { Config(path: "/t/{id}", baseUrl: $0.baseUrl) },
        { Config(path: $0.path, baseUrl: "https://second", headers: ["X-Id": "{id}"], query: ["id": "{id}"]) },
      ],
      id: "7",
    )
    #expect(result == Config(path: "/t/7", baseUrl: "https://second", headers: ["X-Id": "7"], query: ["id": "7"]))
  }

  @Test func withoutAnIdTheTokenStays() async throws {
    let result = try await artwork(Config(path: "/artwork/{id}"), id: nil)
    #expect(result?.path == "/artwork/{id}")
    #expect(RequestLayer.hasUnfilledIdToken(result!))
    #expect(!RequestLayer.hasUnfilledIdToken(Config(path: "/artwork/1")))
    #expect(RequestLayer.hasUnfilledIdToken(Config(headers: ["X": "{id}"])))
  }

  @Test func noPathLeftIsNoArtwork() async throws {
    #expect(try await artwork(over: Config(baseUrl: "https://img")) == nil)
    #expect(try await artwork(transforms: [{ _ in Config(baseUrl: "https://img") }]) == nil)
  }

  @Test func sizeQueryNamesTheContextsWholePixels() {
    let params = ImageQueryParams(width: "w", height: "h")
    #expect(RequestLayer.sizeQuery(params, ImageContext(width: 300.7, height: 200)) == ["w": "300", "h": "200"])
    #expect(RequestLayer.sizeQuery(ImageQueryParams(width: "w"), ImageContext(width: 300, height: 200)) == ["w": "300"])
    #expect(RequestLayer.sizeQuery(params, ImageContext(width: nil, height: 200)) == ["h": "200"])
    #expect(RequestLayer.sizeQuery(params, nil) == [:])
    #expect(RequestLayer.sizeQuery(nil, ImageContext(width: 300, height: 200)) == [:])
  }

  @Test func imageHeadersAddTheUserAgentUnlessAHeaderSetsIt() {
    #expect(RequestLayer.imageHeaders(Config()) == nil)
    #expect(RequestLayer.imageHeaders(Config(userAgent: "UA")) == ["User-Agent": "UA"])
    #expect(RequestLayer.imageHeaders(Config(headers: ["User-Agent": "mine"], userAgent: "UA")) == ["User-Agent": "mine"])
  }

  @Test func imageHeadersAddTheContentTypeUnlessAHeaderSetsIt() {
    #expect(RequestLayer.imageHeaders(Config(contentType: "image/png")) == ["Content-Type": "image/png"])
    #expect(
      RequestLayer.imageHeaders(Config(headers: ["Content-Type": "mine"], contentType: "image/png"))
        == ["Content-Type": "mine"],
    )
    #expect(
      RequestLayer.imageHeaders(Config(contentType: "image/png", userAgent: "UA"))
        == ["Content-Type": "image/png", "User-Agent": "UA"],
    )
  }
}
