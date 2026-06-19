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

// Exercises the full Siri-phrase → search decision via the pure factory,
// standing in for what `INMediaSearch` would deliver for each spoken command.
@Suite("MediaIntentCriteria.from")
struct MediaIntentCriteriaFromTests {
  private let appName = "Radio Garden"

  // "Play Radio Garden" → Siri sends mediaName "Garden" (+ radio mediaType).
  @Test func playAppName_resumes() {
    let c = MediaIntentCriteria.from(mediaName: "Garden", genreNames: [], hasReference: false, hasMediaType: true, appName: appName)
    #expect(c.query == "Garden")
    #expect(c.matchesAppName)
    #expect(c.isResume)
  }

  // Bare "Play Radio Garden" where Siri sends nothing actionable.
  @Test func emptyIntent_resumes() {
    let c = MediaIntentCriteria.from(mediaName: nil, genreNames: [], hasReference: false, hasMediaType: false, appName: appName)
    #expect(c.query == "")
    #expect(c.isResume)
  }

  // "Play jazz on Radio Garden" → genre, not mediaName. The genre becomes the query.
  @Test func genre_becomesQuery_andSearches() {
    let c = MediaIntentCriteria.from(mediaName: nil, genreNames: ["jazz"], hasReference: false, hasMediaType: true, appName: appName)
    #expect(c.query == "jazz")
    #expect(c.hasGenres)
    #expect(!c.isResume)
  }

  // Multi-word genres are joined into the query.
  @Test func multiWordGenre_joined() {
    let c = MediaIntentCriteria.from(mediaName: "   ", genreNames: ["classic", "rock"], hasReference: false, hasMediaType: false, appName: appName)
    #expect(c.query == "classic rock")
    #expect(!c.isResume)
  }

  // "Play KCRW on Radio Garden" → a real station search, not resume.
  @Test func stationName_searches() {
    let c = MediaIntentCriteria.from(mediaName: "KCRW", genreNames: [], hasReference: false, hasMediaType: false, appName: appName)
    #expect(c.query == "KCRW")
    #expect(!c.matchesAppName)
    #expect(!c.isResume)
  }

  // App-name match ignores case and diacritics.
  @Test func appNameMatch_caseAndDiacriticInsensitive() {
    let c = MediaIntentCriteria.from(mediaName: "gärden", genreNames: [], hasReference: false, hasMediaType: false, appName: appName)
    #expect(c.matchesAppName)
    #expect(c.isResume)
  }

  // Without a known app name, an app-name-ish query is just a search.
  @Test func nilAppName_searches() {
    let c = MediaIntentCriteria.from(mediaName: "Garden", genreNames: [], hasReference: false, hasMediaType: false, appName: nil)
    #expect(!c.matchesAppName)
    #expect(!c.isResume)
  }
}
