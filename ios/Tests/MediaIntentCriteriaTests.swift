import Testing

@testable import AudioBrowserTestable

@Suite("MediaIntentCriteria")
struct MediaIntentCriteriaTests {
  @Test func emptyEverything_isResume() {
    let c = MediaIntentCriteria(query: "", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false)
    #expect(c.isResume)
  }

  @Test func whitespaceQuery_isResume() {
    let c = MediaIntentCriteria(query: "   ", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false)
    #expect(c.isResume)
  }

  // currentlyPlaying always resumes — "play this" makes the active track play.
  @Test func currentlyPlaying_isResume() {
    #expect(MediaIntentCriteria(query: "", reference: .currentlyPlaying, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    // even with a stray query/filter, "play this" is a resume
    #expect(MediaIntentCriteria(query: "jazz", reference: .currentlyPlaying, hasGenres: true, hasMediaType: true, matchesAppName: false).isResume)
  }

  // .my always searches — "play my favorites" goes to the consumer, never resume.
  @Test func my_isNotResume() {
    #expect(!MediaIntentCriteria(query: "", reference: .my, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", reference: .my, hasGenres: false, hasMediaType: false, matchesAppName: true).isResume)
  }

  @Test func anyCriteria_isNotResume() {
    #expect(!MediaIntentCriteria(query: "kcrw", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", reference: .unknown, hasGenres: true, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", reference: .unknown, hasGenres: false, hasMediaType: true, matchesAppName: false).isResume)
  }

  @Test func queryNamesApp_isResume() {
    let c = MediaIntentCriteria(query: "Garden", reference: .unknown, hasGenres: false, hasMediaType: true, matchesAppName: true)
    #expect(c.isResume)
  }

  @Test func queryNamesApp_withGenre_isNotResume() {
    #expect(!MediaIntentCriteria(query: "Garden", reference: .unknown, hasGenres: true, hasMediaType: false, matchesAppName: true).isResume)
  }

  @Test func queryNotApp_isNotResume() {
    #expect(!MediaIntentCriteria(query: "jazz", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
  }
}

@Suite("MediaIntentCriteria.from")
struct MediaIntentCriteriaFromTests {
  private func from(
    mediaName: String? = nil,
    genreNames: [String] = [],
    artistName: String? = nil,
    albumName: String? = nil,
    mediaTypeMode: String? = nil,
    reference: MediaIntentCriteria.Reference = .unknown,
    hasMediaType: Bool = false,
    appName: String? = "Radio Garden"
  ) -> MediaIntentCriteria {
    MediaIntentCriteria.from(
      mediaName: mediaName, genreNames: genreNames, artistName: artistName,
      albumName: albumName, mediaTypeMode: mediaTypeMode,
      reference: reference, hasMediaType: hasMediaType, appName: appName
    )
  }

  // MARK: resume vs search

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

  @Test func playMyFavorites_searches() {
    let c = from(reference: .my)
    #expect(!c.isResume)
    #expect(c.reference == .my)
  }

  @Test func stationName_searches() {
    let c = from(mediaName: "KCRW")
    #expect(c.query == "KCRW")
    #expect(!c.matchesAppName)
    #expect(!c.isResume)
    #expect(c.searchMode == nil)
  }

  // MARK: mode comes ONLY from mediaTypeMode (no field-derivation)

  // "Play jazz" → genre is a FILTER, not a mode. mode stays nil; genre set.
  @Test func genre_isFilterNotMode() {
    let c = from(genreNames: ["jazz"], hasMediaType: true)
    #expect(c.query == "jazz")
    #expect(c.searchMode == nil)
    #expect(c.genre == "jazz")
    #expect(c.hasGenres)
    #expect(!c.isResume)
  }

  @Test func multiWordGenre_joined_noMode() {
    let c = from(mediaName: "   ", genreNames: ["classic", "rock"])
    #expect(c.query == "classic rock")
    #expect(c.genre == "classic rock")
    #expect(c.searchMode == nil)
  }

  @Test func artist_isFilterNotMode() {
    let c = from(artistName: "Michael Jackson")
    #expect(c.query == "Michael Jackson")
    #expect(c.searchMode == nil)
    #expect(c.artist == "Michael Jackson")
    #expect(!c.isResume)
  }

  @Test func albumAndArtist_areFiltersNotMode() {
    let c = from(mediaName: "Thriller", artistName: "Michael Jackson", albumName: "Thriller")
    #expect(c.searchMode == nil)
    #expect(c.album == "Thriller")
    #expect(c.artist == "Michael Jackson")
    #expect(!c.query.isEmpty)
  }

  // A vertical mediaType DOES set the mode, and travels with its filters.
  @Test func song_viaMediaTypeMode_setsTitle() {
    let c = from(mediaName: "Billie Jean", mediaTypeMode: "song")
    #expect(c.query == "Billie Jean")
    #expect(c.searchMode == "song")
    #expect(c.title == "Billie Jean")
    #expect(c.playlist == nil)
  }

  @Test func playlist_viaMediaTypeMode_setsPlaylist() {
    let c = from(mediaName: "Workout", mediaTypeMode: "playlist")
    #expect(c.query == "Workout")
    #expect(c.searchMode == "playlist")
    #expect(c.playlist == "Workout")
    #expect(c.title == nil)
  }

  // "Play jazz station" → vertical wins as mode; genre rides as a filter.
  @Test func station_withGenreFilter() {
    let c = from(genreNames: ["jazz"], mediaTypeMode: "station", hasMediaType: true)
    #expect(c.searchMode == "station")
    #expect(c.genre == "jazz")
  }

  @Test func podcast_setsMode() {
    let c = from(mediaName: "Serial", mediaTypeMode: "podcast", hasMediaType: true)
    #expect(c.searchMode == "podcast")
    #expect(c.query == "Serial")
  }

  @Test func plainName_hasNoStructuredFields() {
    let c = from(mediaName: "KCRW")
    #expect(c.searchMode == nil)
    #expect(c.genre == nil)
    #expect(c.artist == nil)
    #expect(c.album == nil)
  }
}
