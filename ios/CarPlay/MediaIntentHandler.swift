import Foundation
import Intents
import os.log

/// Siri's play-media intent, from the phone or CarPlay voice: plays through `MediaIntentPlayback`.
final class MediaIntentHandler: NSObject, INPlayMediaIntentHandling {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "MediaIntentHandler")

  // MARK: - INPlayMediaIntentHandling

  func handle(intent: INPlayMediaIntent, completion: @escaping @Sendable (INPlayMediaIntentResponse) -> Void) {
    let s = intent.mediaSearch
    let criteria = MediaIntentCriteria(
      mediaName: s?.mediaName,
      genreNames: s?.genreNames ?? [],
      artistName: s?.artistName,
      albumName: s?.albumName,
      mediaTypeMode: Self.mediaTypeMode(s?.mediaType ?? .unknown),
      reference: Self.reference(s?.reference ?? .unknown),
      hasMediaType: (s?.mediaType ?? .unknown) != .unknown,
      appName: Self.hostAppName(),
    )
    Self.logger.info("Play media intent — query=\(criteria.query) matchesApp=\(criteria.matchesAppName) resume=\(criteria.isResume)")

    Task { @MainActor in
      let success = await withDeadline(MediaIntentPlayback.budget) { deadline in
        await MediaIntentPlayback.play(criteria: criteria, deadline: deadline)
      }
      if success == nil {
        Self.logger.error("Play media intent — no answer within \(MediaIntentPlayback.budget)")
      }
      completion(INPlayMediaIntentResponse(code: success == true ? .success : .failure, userActivity: nil))
    }
  }

  /// The `SearchMode` name of a container vertical; a name `SearchMode(fromString:)` does not
  /// know silently drops the mode. Nil for unclassified types and for album, artist and genre,
  /// which are filters, not verticals.
  private static func mediaTypeMode(_ type: INMediaItemType) -> String? {
    switch type {
    case .station, .radioStation, .algorithmicRadioStation, .musicStation: "station"
    case .podcastShow, .podcastEpisode, .podcastPlaylist, .podcastStation: "podcast"
    case .audioBook: "audiobook"
    case .news: "news"
    case .music: "music"
    case .song: "song"
    case .playlist: "playlist"
    case .musicVideo: "music-video"
    case .movie: "movie"
    case .tvShow: "tv-show"
    case .tvShowEpisode: "tv-show-episode"
    default: nil
    }
  }

  private static func reference(_ ref: INMediaReference) -> MediaIntentCriteria.Reference {
    switch ref {
    case .currentlyPlaying: .currentlyPlaying
    case .my: .my
    default: .unknown
    }
  }

  private static func hostAppName() -> String? {
    let info = Bundle.main.infoDictionary
    return (info?["CFBundleDisplayName"] as? String) ?? (info?["CFBundleName"] as? String)
  }
}
