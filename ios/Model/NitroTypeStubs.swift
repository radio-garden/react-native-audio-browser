#if !canImport(NitroModules)
/// Lightweight stand-ins for NitroModules types used by the testable target.
/// Only compiled when NitroModules is unavailable (SPM test builds).

struct TrackRequest: Equatable {
  var userAgent: String? = nil
  var headers: [String: String]? = nil
  var query: [String: String]? = nil
}

struct Track: Equatable {
  var id: String
  var url: String? = nil
  var src: String? = nil
  var request: TrackRequest? = nil
  var title: String = ""
  var artist: String? = nil
  var album: String? = nil
  var live: Bool? = nil
}

struct TrackLoadEvent {
  var track: Track
  var queue: [Track]
  var startIndex: Double
}

enum RepeatMode {
  case off
  case track
  case queue
}

enum CarPlaySiriListButtonPosition {
  case top
  case bottom

  init?(fromString string: String) {
    switch string {
    case "top": self = .top
    case "bottom": self = .bottom
    default: return nil
    }
  }
}

struct ImageSource: Equatable {
  var uri: String
  var method: String? = nil
  var headers: [String: String]? = nil
  var body: String? = nil
}

enum TrackStyle {
  case list
  case grid

  init?(fromString string: String) {
    switch string {
    case "list": self = .list
    case "grid": self = .grid
    default: return nil
    }
  }
}

struct ImageRowItem: Equatable {
  var url: String? = nil
  var artwork: String? = nil
  var artworkSource: ImageSource? = nil
  var title: String
}

struct ResolvedTrack: Equatable {
  var url: String
  var children: [Track]? = nil
  var carPlaySiriListButton: CarPlaySiriListButtonPosition? = nil
  var id: String? = nil
  var src: String? = nil
  var artwork: String? = nil
  var artworkSource: ImageSource? = nil
  var request: TrackRequest? = nil
  var artworkCarPlayTinted: Bool? = nil
  var title: String
  var subtitle: String? = nil
  var artist: String? = nil
  var album: String? = nil
  var description: String? = nil
  var genre: String? = nil
  var duration: Double? = nil
  var style: TrackStyle? = nil
  var childrenStyle: TrackStyle? = nil
  var favorited: Bool? = nil
  var groupTitle: String? = nil
  var live: Bool? = nil
  var imageRow: [ImageRowItem]? = nil
}

enum PlaybackState: Equatable {
  case none
  case ready
  case playing
  case paused
  case stopped
  case loading
  case buffering
  case error
  case ended
}

struct TrackMetadata: Equatable {
  var title: String?
  var artist: String?
  var albumTitle: String?
  var subtitle: String?
  var description: String?
  var artworkUri: String?
  var trackNumber: String?
  var composer: String?
  var conductor: String?
  var genre: String?
  var compilation: String?
  var station: String?
  var mediaType: String?
  var creationDate: String?
  var creationYear: String?
  var url: String?

  init(
    title: String? = nil, artist: String? = nil, albumTitle: String? = nil,
    subtitle: String? = nil, description: String? = nil, artworkUri: String? = nil,
    trackNumber: String? = nil, composer: String? = nil, conductor: String? = nil,
    genre: String? = nil, compilation: String? = nil, station: String? = nil,
    mediaType: String? = nil, creationDate: String? = nil, creationYear: String? = nil,
    url: String? = nil
  ) {
    self.title = title
    self.artist = artist
    self.albumTitle = albumTitle
    self.subtitle = subtitle
    self.description = description
    self.artworkUri = artworkUri
    self.trackNumber = trackNumber
    self.composer = composer
    self.conductor = conductor
    self.genre = genre
    self.compilation = compilation
    self.station = station
    self.mediaType = mediaType
    self.creationDate = creationDate
    self.creationYear = creationYear
    self.url = url
  }
}

struct TimedMetadata: Equatable {
  var title: String?
  var artist: String?
  var album: String?
  var date: String?
  var genre: String?

  init(
    title: String? = nil, artist: String? = nil, album: String? = nil,
    date: String? = nil, genre: String? = nil
  ) {
    self.title = title
    self.artist = artist
    self.album = album
    self.date = date
    self.genre = genre
  }
}

struct ChapterMetadata: Equatable {
  var startTime: Double
  var endTime: Double
  var title: String?
  var url: String?

  init(startTime: Double, endTime: Double, title: String? = nil, url: String? = nil) {
    self.startTime = startTime
    self.endTime = endTime
    self.title = title
    self.url = url
  }
}

// MARK: - Playback Event Types (used by PlaybackCoordinator)

struct PlaybackError: Equatable {
  var code: String
  var message: String
}

struct Playback: Equatable {
  var state: PlaybackState
  var error: PlaybackError?
}

struct PlaybackErrorEvent {
  var error: PlaybackError?
}

struct PlaybackActiveTrackChangedEvent {
  var lastIndex: Double?
  var lastTrack: Track?
  var lastPosition: Double
  var index: Double?
  var track: Track?
}

struct PlaybackProgressUpdatedEvent {
  var track: Double
  var position: Double
  var duration: Double
  var buffered: Double
}

struct PlayingState: Equatable {
  var playing: Bool
  var buffering: Bool
}

struct PlaybackQueueEndedEvent {
  var track: Double
  var position: Double
}

struct RepeatModeChangedEvent {
  var repeatMode: RepeatMode
}

// MARK: - TrackPlayerError.PlaybackError Nitro Conversion Stub

extension TrackPlayerError.PlaybackError {
  func toNitroError() -> PlaybackError {
    let code = switch self {
    case .failedToLoadKeyValue:
      "failed-to-load"
    case .invalidSourceUrl:
      "invalid-source-url"
    case .notConnectedToInternet:
      "not-connected-to-internet"
    case .playbackFailed:
      "playback-failed"
    case .trackWasUnplayable:
      "track-unplayable"
    }
    return PlaybackError(code: code, message: errorDescription ?? "Unknown error")
  }
}
#endif
