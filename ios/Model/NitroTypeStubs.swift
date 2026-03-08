#if !canImport(NitroModules)
/// Lightweight stand-ins for NitroModules types used by the testable target.
/// Only compiled when NitroModules is unavailable (SPM test builds).

struct Track: Equatable {
  var id: String
  var url: String? = nil
  var src: String? = nil
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
  var src: String? = nil
  var artwork: String? = nil
  var artworkSource: ImageSource? = nil
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
#endif
