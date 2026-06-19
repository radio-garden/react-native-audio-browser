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

// Exercises the full Siri-phrase → search decision (resume vs search) AND the
// structured-params mapping, via the pure factory — standing in for what
// `INMediaSearch` would deliver for each spoken command.
@Suite("MediaIntentCriteria.from")
struct MediaIntentCriteriaFromTests {
  private func from(
    mediaName: String? = nil,
    genreNames: [String] = [],
    artistName: String? = nil,
    albumName: String? = nil,
    mediaTypeKind: String? = nil,
    hasReference: Bool = false,
    hasMediaType: Bool = false,
    appName: String? = "Radio Garden"
  ) -> MediaIntentCriteria {
    MediaIntentCriteria.from(
      mediaName: mediaName, genreNames: genreNames, artistName: artistName,
      albumName: albumName, mediaTypeKind: mediaTypeKind,
      hasReference: hasReference, hasMediaType: hasMediaType, appName: appName
    )
  }

  // MARK: resume vs search

  // "Play Radio Garden" → mediaName "Garden" (+ radio mediaType) → resume.
  @Test func playAppName_resumes() {
    let c = from(mediaName: "Garden", hasMediaType: true)
    #expect(c.query == "Garden")
    #expect(c.matchesAppName)
    #expect(c.isResume)
  }

  @Test func emptyIntent_resumes() {
    let c = from()
    #expect(c.query == "")
    #expect(c.isResume)
  }

  @Test func stationName_searches() {
    let c = from(mediaName: "KCRW")
    #expect(c.query == "KCRW")
    #expect(!c.matchesAppName)
    #expect(!c.isResume)
    #expect(c.searchMode == nil)
  }

  @Test func appNameMatch_caseAndDiacriticInsensitive() {
    let c = from(mediaName: "gärden")
    #expect(c.matchesAppName)
    #expect(c.isResume)
  }

  @Test func nilAppName_searches() {
    let c = from(mediaName: "Garden", appName: nil)
    #expect(!c.matchesAppName)
    #expect(!c.isResume)
  }

  // MARK: structured search params (mode + fields)

  // "Play jazz on Radio Garden" → genre → mode=genre, genre="jazz", q="jazz".
  @Test func genre_setsModeAndQuery() {
    let c = from(genreNames: ["jazz"], hasMediaType: true)
    #expect(c.query == "jazz")
    #expect(c.searchMode == "genre")
    #expect(c.genre == "jazz")
    #expect(c.hasGenres)
    #expect(!c.isResume)
  }

  @Test func multiWordGenre_joined() {
    let c = from(mediaName: "   ", genreNames: ["classic", "rock"])
    #expect(c.query == "classic rock")
    #expect(c.genre == "classic rock")
    #expect(c.searchMode == "genre")
  }

  // mediaName empty, artist set → query falls back to the artist.
  @Test func artist_setsModeAndFallbackQuery() {
    let c = from(artistName: "Michael Jackson")
    #expect(c.query == "Michael Jackson")
    #expect(c.searchMode == "artist")
    #expect(c.artist == "Michael Jackson")
    #expect(!c.isResume)
  }

  // Album is more specific than artist, so it wins the mode.
  @Test func album_modeWinsOverArtist() {
    let c = from(mediaName: "Thriller", artistName: "Michael Jackson", albumName: "Thriller")
    #expect(c.searchMode == "album")
    #expect(c.album == "Thriller")
    #expect(c.artist == "Michael Jackson")
    #expect(!c.query.isEmpty)
  }

  // Song comes through mediaType (not a dedicated field) → mode=song, title set.
  @Test func song_viaMediaType_setsTitle() {
    let c = from(mediaName: "Billie Jean", mediaTypeKind: "song")
    #expect(c.query == "Billie Jean")
    #expect(c.searchMode == "song")
    #expect(c.title == "Billie Jean")
    #expect(c.playlist == nil)
  }

  // Playlist likewise comes through mediaType → mode=playlist, playlist set.
  @Test func playlist_viaMediaType_setsPlaylist() {
    let c = from(mediaName: "Workout", mediaTypeKind: "playlist")
    #expect(c.query == "Workout")
    #expect(c.searchMode == "playlist")
    #expect(c.playlist == "Workout")
    #expect(c.title == nil)
  }

  // A plain station name carries no structured fields.
  @Test func plainName_hasNoStructuredFields() {
    let c = from(mediaName: "KCRW")
    #expect(c.searchMode == nil)
    #expect(c.genre == nil)
    #expect(c.artist == nil)
    #expect(c.album == nil)
  }
}
