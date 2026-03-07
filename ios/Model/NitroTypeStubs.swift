#if !canImport(NitroModules)
/// Lightweight stand-ins for NitroModules types used by the testable target.
/// Only compiled when NitroModules is unavailable (SPM test builds).

struct Track {
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
#endif
