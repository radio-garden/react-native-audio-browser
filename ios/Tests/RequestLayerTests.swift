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
}
