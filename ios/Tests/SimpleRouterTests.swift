import Testing

@testable import AudioBrowserTestable

private struct TestRoute: RouteEntry {
  let path: String
}

private func match(_ path: String, _ patterns: [String]) -> (route: TestRoute, match: RouteMatch)? {
  SimpleRouter.findBestMatch(path: path, routes: patterns.map { TestRoute(path: $0) })
}

// MARK: - exact matches

@Test func matchesASimplePath() {
  let result = match("/artists", ["/artists"])
  #expect(result != nil)
  #expect(result!.route.path == "/artists")
  #expect(result!.match.params.isEmpty)
}

@Test func matchesMultiSegmentPaths() {
  let result = match("/artists/top/rated", ["/artists/top/rated"])
  #expect(result != nil)
  #expect(result!.route.path == "/artists/top/rated")
}

@Test func returnsNilWhenNoRouteMatches() {
  #expect(match("/unknown", ["/artists"]) == nil)
}

@Test func returnsNilWhenSegmentCountDiffers() {
  #expect(match("/artists/123", ["/artists"]) == nil)
}

@Test func returnsNilWhenRoutesAreEmpty() {
  #expect(match("/artists", []) == nil)
}

@Test func treatsTrailingSlashesTheSameAsWithout() {
  let result = match("/artists/", ["/artists"])
  #expect(result != nil)
  #expect(result!.route.path == "/artists")
}

// MARK: - parameter extraction

@Test func extractsASingleParameter() {
  let result = match("/artists/123", ["/artists/{id}"])
  #expect(result != nil)
  #expect(result!.match.params == ["id": "123"])
}

@Test func extractsMultipleParameters() {
  let result = match("/artists/123/albums/456", ["/artists/{artistId}/albums/{albumId}"])
  #expect(result != nil)
  #expect(result!.match.params == ["artistId": "123", "albumId": "456"])
}

// MARK: - single wildcard

@Test func matchesAnySingleSegment() {
  #expect(match("/artists/anything", ["/artists/*"]) != nil)
}

@Test func doesNotExtractWildcardValueIntoParams() {
  let result = match("/artists/anything", ["/artists/*"])
  #expect(result!.match.params.isEmpty)
}

// MARK: - tail wildcard

@Test func matchesWithNoRemainingSegmentsAndHasNoTailParam() {
  let result = match("/files", ["/files/**"])
  #expect(result != nil)
  #expect(result!.match.params.isEmpty)
}

@Test func matchesWithRemainingSegmentsAndCapturesTail() {
  let result = match("/files/a/b/c", ["/files/**"])
  #expect(result != nil)
  #expect(result!.match.params == ["tail": "a/b/c"])
}

@Test func matchesWithASingleRemainingSegment() {
  let result = match("/files/readme.txt", ["/files/**"])
  #expect(result != nil)
  #expect(result!.match.params == ["tail": "readme.txt"])
}

@Test func returnsNilWhenPrefixSegmentsDoNotMatch() {
  #expect(match("/other/a/b", ["/files/**"]) == nil)
}

@Test func worksWithParametersBeforeTailWildcard() {
  let result = match("/api/v1/users/list", ["/api/{version}/**"])
  #expect(result != nil)
  #expect(result!.match.params == ["version": "v1", "tail": "users/list"])
}

@Test func matchesAnyPathWithBareTailWildcardPattern() {
  let result = match("/any/path/here", ["/**"])
  #expect(result != nil)
  #expect(result!.match.params == ["tail": "any/path/here"])
}

// MARK: - specificity

@Test func prefersConstantSegmentsOverParameters() {
  let result = match("/artists/top", ["/artists/{id}", "/artists/top"])
  #expect(result!.route.path == "/artists/top")
}

@Test func prefersParametersOverWildcards() {
  let result = match("/artists/123", ["/artists/*", "/artists/{id}"])
  #expect(result!.route.path == "/artists/{id}")
}

@Test func prefersExactMatchOverTailWildcard() {
  let result = match("/api/v1", ["/api/**", "/api/{version}"])
  #expect(result!.route.path == "/api/{version}")
}

@Test func computesCorrectSpecificityForTailWildcardPatterns() {
  // Regression test for issue #27: specificity must account for
  // segment types before the ** wildcard.
  let result = match("/api/v1/users", ["/api/v1/**"])
  // constant "api" (1000) + constant "v1" (1000) + tail(1) + 3 segments = 2004
  #expect(result!.match.specificity == 2004)
}

@Test func prefersConstantPrefixOverParameterPrefixInTailWildcards() {
  // Second part of issue #27: with correct specificity, the constant
  // prefix should beat the parameter prefix.
  let result = match("/api/v1/users", ["/api/{version}/**", "/api/v1/**"])
  #expect(result!.route.path == "/api/v1/**")
}

@Test func treatsADoubleStarBeforeTheEndAsAConstant() {
  #expect(match("/a/**/b", ["/a/**/b"]) != nil)
  #expect(match("/a/x/b", ["/a/**/b"]) == nil)
}

@Test func theFirstRouteWinsATie() {
  let result = match("/artists/123", ["/artists/{id}", "/artists/{name}"])
  #expect(result!.route.path == "/artists/{id}")
}

// MARK: - browse route

private func browseRoute(_ path: String, _ patterns: [String]) -> (route: TestRoute, match: RouteMatch)? {
  SimpleRouter.browseRoute(for: path, in: patterns.map { TestRoute(path: $0) })
}

@Test func theSpecialRoutesNeverMatchABrowsePath() {
  #expect(browseRoute("/__tabs__", ["__tabs__", "__search__"]) == nil)
  #expect(browseRoute("__search__", ["__search__"]) == nil)
}

@Test func aMatchingRouteWinsOverTheDefault() {
  let result = browseRoute("/artists/1", ["__default__", "/artists/{id}"])
  #expect(result!.route.path == "/artists/{id}")
  #expect(result!.match.params == ["id": "1"])
}

@Test func withoutAMatchTheDefaultRouteBrowsesThePath() {
  let result = browseRoute("/a/b", ["/artists", "__default__"])
  #expect(result!.route.path == "__default__")
  #expect(result!.match.params == ["path": "/a/b"])
}

@Test func withoutAMatchOrADefaultThereIsNoBrowseRoute() {
  #expect(browseRoute("/a/b", ["/artists", "__tabs__"]) == nil)
}
