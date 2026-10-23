import Testing

@testable import AudioBrowserTestable

private func criteria(
  mediaName: String? = nil,
  genreNames: [String] = [],
  artistName: String? = nil,
  albumName: String? = nil,
  mediaTypeMode: String? = nil,
  reference: MediaIntentCriteria.Reference = .unknown,
  hasMediaType: Bool = false,
  appName: String? = "Acme Radio",
) -> MediaIntentCriteria {
  MediaIntentCriteria(
    mediaName: mediaName, genreNames: genreNames, artistName: artistName,
    albumName: albumName, mediaTypeMode: mediaTypeMode,
    reference: reference, hasMediaType: hasMediaType, appName: appName,
  )
}

@Suite("MediaIntentCriteria")
struct MediaIntentCriteriaTests {
  @Test func emptyEverything_isResume() {
    #expect(criteria().isResume)
  }

  @Test func whitespaceQuery_isResume() {
    let c = criteria(mediaName: "   ")
    #expect(c.query == "")
    #expect(c.isResume)
  }

  // currentlyPlaying always resumes — "play this" makes the active track play.
  @Test func currentlyPlaying_isResume() {
    #expect(criteria(reference: .currentlyPlaying).isResume)
    // even with a stray query/filter, "play this" is a resume
    #expect(criteria(mediaName: "jazz", genreNames: ["jazz"], reference: .currentlyPlaying, hasMediaType: true).isResume)
  }

  // .my always searches — "play my favorites" goes to the consumer, never resume.
  @Test func my_isNotResume() {
    #expect(!criteria(reference: .my).isResume)
    let namesApp = criteria(mediaName: "Acme", reference: .my)
    #expect(namesApp.matchesAppName)
    #expect(!namesApp.isResume)
  }

  @Test func anyCriteria_isNotResume() {
    #expect(!criteria(mediaName: "kcrw").isResume)
    #expect(!criteria(genreNames: ["jazz"]).isResume)
    #expect(!criteria(hasMediaType: true).isResume)
  }

  @Test func queryNamesApp_isResume() {
    let c = criteria(mediaName: "Acme", hasMediaType: true)
    #expect(c.matchesAppName)
    #expect(c.isResume)
  }

  @Test func queryNamesApp_withGenre_isNotResume() {
    let c = criteria(mediaName: "Acme", genreNames: ["jazz"])
    #expect(c.matchesAppName)
    #expect(!c.isResume)
  }

  @Test func queryNotApp_isNotResume() {
    #expect(!criteria(mediaName: "jazz").isResume)
  }

  // An empty genre name is still a genre filter: the fact is that genres were given.
  @Test func emptyGenreName_isStillAGenreFilter() {
    let c = criteria(genreNames: [""])
    #expect(c.genre == "")
    #expect(!c.isResume)
  }
}

@Suite("MediaIntentCriteria from intent fields")
struct MediaIntentCriteriaFromTests {
  // MARK: resume vs search

  @Test func playAppName_resumes() {
    let c = criteria(mediaName: "Acme", hasMediaType: true)
    #expect(c.query == "Acme")
    #expect(c.matchesAppName)
    #expect(c.isResume)
  }

  @Test func emptyIntent_resumes() {
    let c = criteria()
    #expect(c.query == "")
    #expect(c.isResume)
  }

  @Test func playMyFavorites_searches() {
    let c = criteria(reference: .my)
    #expect(!c.isResume)
    #expect(c.reference == .my)
  }

  @Test func stationName_searches() {
    let c = criteria(mediaName: "KCRW")
    #expect(c.query == "KCRW")
    #expect(!c.matchesAppName)
    #expect(!c.isResume)
    #expect(c.searchMode == nil)
  }

  // MARK: mode comes ONLY from mediaTypeMode (no field-derivation)

  // "Play jazz" → genre is a FILTER, not a mode. mode stays nil; genre set.
  @Test func genre_isFilterNotMode() {
    let c = criteria(genreNames: ["jazz"], hasMediaType: true)
    #expect(c.query == "jazz")
    #expect(c.searchMode == nil)
    #expect(c.genre == "jazz")
    #expect(!c.isResume)
  }

  @Test func multiWordGenre_joined_noMode() {
    let c = criteria(mediaName: "   ", genreNames: ["classic", "rock"])
    #expect(c.query == "classic rock")
    #expect(c.genre == "classic rock")
    #expect(c.searchMode == nil)
  }

  @Test func artist_isFilterNotMode() {
    let c = criteria(artistName: "Michael Jackson")
    #expect(c.query == "Michael Jackson")
    #expect(c.searchMode == nil)
    #expect(c.artist == "Michael Jackson")
    #expect(!c.isResume)
  }

  @Test func albumAndArtist_areFiltersNotMode() {
    let c = criteria(mediaName: "Thriller", artistName: "Michael Jackson", albumName: "Thriller")
    #expect(c.searchMode == nil)
    #expect(c.album == "Thriller")
    #expect(c.artist == "Michael Jackson")
    #expect(!c.query.isEmpty)
  }

  // A vertical mediaType DOES set the mode, and travels with its filters.
  @Test func song_viaMediaTypeMode_setsTitle() {
    let c = criteria(mediaName: "Billie Jean", mediaTypeMode: "song")
    #expect(c.query == "Billie Jean")
    #expect(c.searchMode == "song")
    #expect(c.title == "Billie Jean")
    #expect(c.playlist == nil)
  }

  @Test func playlist_viaMediaTypeMode_setsPlaylist() {
    let c = criteria(mediaName: "Workout", mediaTypeMode: "playlist")
    #expect(c.query == "Workout")
    #expect(c.searchMode == "playlist")
    #expect(c.playlist == "Workout")
    #expect(c.title == nil)
  }

  // "Play jazz station" → vertical wins as mode; genre rides as a filter.
  @Test func station_withGenreFilter() {
    let c = criteria(genreNames: ["jazz"], mediaTypeMode: "station", hasMediaType: true)
    #expect(c.searchMode == "station")
    #expect(c.genre == "jazz")
  }

  @Test func podcast_setsMode() {
    let c = criteria(mediaName: "Serial", mediaTypeMode: "podcast", hasMediaType: true)
    #expect(c.searchMode == "podcast")
    #expect(c.query == "Serial")
  }

  @Test func plainName_hasNoStructuredFields() {
    let c = criteria(mediaName: "KCRW")
    #expect(c.searchMode == nil)
    #expect(c.genre == nil)
    #expect(c.artist == nil)
    #expect(c.album == nil)
  }
}
