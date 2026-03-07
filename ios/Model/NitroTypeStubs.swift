#if !canImport(NitroModules)
/// Lightweight stand-ins for NitroModules types used by QueueManager.
/// Only compiled when NitroModules is unavailable (SPM test builds).

struct Track {
  var id: String
}

enum RepeatMode {
  case off
  case track
  case queue
}
#endif
