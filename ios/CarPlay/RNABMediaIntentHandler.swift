import Foundation
import Intents
import os.log

/// In-app INPlayMediaIntent handler (Siri / CarPlay voice). Internal so Intents
/// types stay out of the generated header; exposed to the ObjC runtime via
/// `@objc(RNABMediaIntentHandler)` and vended from `RNABAudioBrowser.handlerForIntent(_:)`.
@objc(RNABMediaIntentHandler)
class RNABMediaIntentHandler: NSObject, INPlayMediaIntentHandling {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "MediaIntentHandler")

  // MARK: - INPlayMediaIntentHandling

  func handle(intent: INPlayMediaIntent, completion: @escaping @Sendable (INPlayMediaIntentResponse) -> Void) {
    let s = intent.mediaSearch
    let criteria = MediaIntentCriteria.from(
      mediaName: s?.mediaName,
      genreNames: s?.genreNames ?? [],
      artistName: s?.artistName,
      albumName: s?.albumName,
      mediaTypeMode: Self.mediaTypeMode(s?.mediaType ?? .unknown),
      reference: Self.reference(s?.reference ?? .unknown),
      hasMediaType: (s?.mediaType ?? .unknown) != .unknown,
      appName: Self.hostAppName()
    )
    Self.logger.info("Play media intent — query=\(criteria.query) matchesApp=\(criteria.matchesAppName) resume=\(criteria.isResume)")

    // Static + gate-waiting, so it works even before the shared instance exists
    // (background intent launch, RN not booted yet).
    HybridAudioBrowser.handlePlayMediaIntent(criteria: criteria) { success in
      completion(INPlayMediaIntentResponse(code: success ? .success : .failure, userActivity: nil))
    }
  }

  /// Collapse `INMediaItemType` to a `SearchMode` string (the container
  /// vertical), or nil for filter-only / unclassified types. `genre`/`artist`/
  /// `album` are filters, not verticals — they yield no mode.
  private static func mediaTypeMode(_ type: INMediaItemType) -> String? {
    switch type {
    case .station, .radioStation, .algorithmicRadioStation, .musicStation: return "station"
    case .podcastShow, .podcastEpisode, .podcastPlaylist, .podcastStation:  return "podcast"
    case .audioBook:       return "audiobook"
    case .news:            return "news"
    case .music:           return "music"
    case .song:            return "song"
    case .playlist:        return "playlist"
    case .musicVideo:      return "music-video"
    case .movie:           return "movie"
    case .tvShow:          return "tv-show"
    case .tvShowEpisode:   return "tv-show-episode"
    default:               return nil   // album/artist/genre/unknown
    }
  }

  /// Map the SiriKit reference to the pure criteria enum. `.currentlyPlaying`
  /// routes to native resume; `.my` to the consumer; everything else is unknown.
  private static func reference(_ ref: INMediaReference) -> MediaIntentCriteria.Reference {
    switch ref {
    case .currentlyPlaying: return .currentlyPlaying
    case .my:               return .my
    default:                return .unknown
    }
  }

  /// Host app's display name, used to recognise "Play «app»" as a resume.
  private static func hostAppName() -> String? {
    let info = Bundle.main.infoDictionary
    return (info?["CFBundleDisplayName"] as? String) ?? (info?["CFBundleName"] as? String)
  }
}
