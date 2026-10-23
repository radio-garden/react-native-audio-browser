import Testing

@testable import AudioBrowserTestable

struct SearchParamsQueryTests {
  private func params(
    _ query: String,
    mode: SearchMode? = nil,
    genre: String? = nil,
    artist: String? = nil,
    album: String? = nil,
    title: String? = nil,
    playlist: String? = nil,
    reference: MediaReference = .unknown,
  ) -> SearchParams {
    SearchParams(
      mode: mode, query: query, genre: genre, artist: artist, album: album,
      title: title, playlist: playlist, reference: reference,
    )
  }

  @Test func aPlainSearchSendsOnlyQ() {
    #expect(params("jazz").queryParams == ["q": "jazz"])
  }

  @Test func everySetFilterIsSent() {
    let all = params(
      "x", mode: .musicVideo, genre: "g", artist: "a", album: "al", title: "t", playlist: "p", reference: .my,
    )
    #expect(all.queryParams == [
      "q": "x", "mode": "music-video", "genre": "g", "artist": "a", "album": "al",
      "title": "t", "playlist": "p", "reference": "my",
    ])
  }

  @Test func anUnknownReferenceIsNotSent() {
    #expect(params("x", reference: .unknown).queryParams["reference"] == nil)
  }

  @Test func theCacheKeyIsSortedAndDelimited() {
    #expect(params("jazz", genre: "jazz").cacheKey == "genre=jazz\u{1}q=jazz")
  }

  @Test func searchesWithTheSameQueryButDifferentFiltersHaveDifferentKeys() {
    #expect(params("jazz").cacheKey != params("jazz", genre: "jazz").cacheKey)
    #expect(params("a", artist: "b").cacheKey != params("a", album: "b").cacheKey)
  }
}
