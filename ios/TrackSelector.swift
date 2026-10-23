import Foundation
import os.log

// MARK: - Protocols for testability

@MainActor protocol TrackSelectionBrowser {
  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool
  func expandQueueFromContextualPath(_ path: String) async throws -> (tracks: [Track], selectedIndex: Int)?
}

@MainActor protocol TrackSelectionPlayer {
  var tracks: [Track] { get }
  var queueSourcePath: String? { get }
}

/// Encapsulates the shared track selection decision tree used by both
/// HybridAudioBrowser and CarPlaySession.
///
/// Resolves a track selection to a concrete `SelectionResult` describing
/// what the caller should do. Each caller then executes the action in its
/// own way (CarPlay: showNowPlaying + completion; HybridAudioBrowser:
/// direct player calls).
@MainActor
class TrackSelector {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "TrackSelector")
  private nonisolated(unsafe) let browserManager: any TrackSelectionBrowser

  nonisolated init(browserManager: any TrackSelectionBrowser) {
    self.browserManager = browserManager
  }

  // MARK: - Result Types

  enum SelectionResult {
    /// Playback action determined and not intercepted by JS handler.
    case play(PlaybackIntent)
    /// JS handleTrackLoad handler intercepted — caller should not play.
    case intercepted
    /// Track is browsable — caller should navigate to this path.
    case browse(path: String)
    /// Nothing to do (no src, no path).
    case none
  }

  enum PlaybackIntent {
    /// Skip to an index in the existing queue.
    case skipTo(index: Int)
    /// Replace queue with new tracks.
    case setQueue(tracks: [Track], startIndex: Int, sourcePath: String?)
    /// Load a single track.
    case loadTrack(Track)
  }

  // MARK: - Selection

  /// Resolves a track selection to a concrete action.
  /// Handles contextual path expansion, queue reuse, and handler interception.
  func select(
    track: Track,
    player: some TrackSelectionPlayer,
  ) async -> SelectionResult {
    // A disabled track is unavailable — it never plays, whichever surface or
    // stale resume path delivered the selection (Track.disabled).
    if track.disabled == true {
      return .none
    }

    let playback: PlannedPlayback
    if let path = track.path, BrowserPathHelper.isContextual(path) {
      playback = await contextualPlayback(path, track: track, player: player)
    } else if track.src != nil {
      playback = .single(track)
    } else if let path = track.path {
      return .browse(path: path)
    } else {
      return .none
    }

    let event = TrackLoadEvent(track: track, queue: playback.queue, startIndex: Double(playback.index))
    if await browserManager.awaitTrackLoadHandler(event: event) {
      return .intercepted
    }
    return .play(playback.intent)
  }

  // MARK: - Private Helpers

  /// What a selection plays, and the queue and index the track-load handler is shown.
  private struct PlannedPlayback {
    let queue: [Track]
    let index: Int
    let intent: PlaybackIntent

    static func single(_ track: Track) -> PlannedPlayback {
      PlannedPlayback(queue: [track], index: 0, intent: .loadTrack(track))
    }
  }

  private func contextualPlayback(
    _ path: String,
    track: Track,
    player: some TrackSelectionPlayer,
  ) async -> PlannedPlayback {
    let parentPath = BrowserPathHelper.stripTrackId(path)
    let trackId = BrowserPathHelper.extractTrackId(path)

    // Check if queue already came from this parent path — just skip to the
    // track. Exact-surface match first: a contextual path carries the tapped
    // page position (`__index`), so path equality pins the exact copy when
    // the page holds the same identity more than once. The identity match
    // remains for index-less paths (e.g. pre-stamp persisted state); an
    // index-stamped path with no exact match falls through to expansion,
    // which re-scopes the queue to the tapped section.
    if parentPath == player.queueSourcePath {
      var skipIndex = player.tracks.firstIndex(where: { $0.path == path })
      if skipIndex == nil, BrowserPathHelper.extractIndex(path) == nil, let trackId {
        skipIndex = player.tracks.firstIndex(where: { $0.identity == trackId })
      }
      if let index = skipIndex {
        logger.debug("Queue already from \(parentPath), skipping to index \(index)")
        return PlannedPlayback(queue: player.tracks, index: index, intent: .skipTo(index: index))
      }
    }

    do {
      guard let (tracks, startIndex) = try await browserManager.expandQueueFromContextualPath(path) else {
        return .single(track)
      }
      return PlannedPlayback(
        queue: tracks, index: startIndex,
        intent: .setQueue(tracks: tracks, startIndex: startIndex, sourcePath: parentPath),
      )
    } catch {
      logger.error("Error expanding queue: \(error.localizedDescription)")
      return .single(track)
    }
  }
}
