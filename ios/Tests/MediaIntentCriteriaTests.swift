import Testing

@testable import AudioBrowserTestable

@Suite("MediaIntentCriteria")
struct MediaIntentCriteriaTests {
  @Test func emptyEverything_isResume() {
    let c = MediaIntentCriteria(query: "", hasReference: false, hasGenres: false, hasMediaType: false, matchesAppName: false)
    #expect(c.isResume)
  }

  @Test func whitespaceQuery_isResume() {
    let c = MediaIntentCriteria(query: "   ", hasReference: false, hasGenres: false, hasMediaType: false, matchesAppName: false)
    #expect(c.isResume)
  }

  @Test func anyCriteria_isNotResume() {
    #expect(!MediaIntentCriteria(query: "kcrw", hasReference: false, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: true, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: false, hasGenres: true, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: false, hasGenres: false, hasMediaType: true, matchesAppName: false).isResume)
  }

  // "Play Radio Garden" → Siri sends mediaName "Garden" (+ radio mediaType).
  // The query names the app, so it's a resume, not a search — even with mediaType.
  @Test func queryNamesApp_isResume() {
    let c = MediaIntentCriteria(query: "Garden", hasReference: false, hasGenres: false, hasMediaType: true, matchesAppName: true)
    #expect(c.isResume)
  }

  // A real filter alongside the app-name query means an actual search, not resume.
  @Test func queryNamesApp_withGenre_isNotResume() {
    #expect(!MediaIntentCriteria(query: "Garden", hasReference: false, hasGenres: true, hasMediaType: false, matchesAppName: true).isResume)
    #expect(!MediaIntentCriteria(query: "Garden", hasReference: true, hasGenres: false, hasMediaType: false, matchesAppName: true).isResume)
  }

  // A query that isn't the app name is a genuine search.
  @Test func queryNotApp_isNotResume() {
    #expect(!MediaIntentCriteria(query: "jazz", hasReference: false, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
  }
}
