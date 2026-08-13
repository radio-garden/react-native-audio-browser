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
/// HybridAudioBrowser and CarPlayController.
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
    let path = track.path

    // 1. Contextual path (playable-only track with queue context)
    if let path, BrowserPathHelper.isContextual(path) {
      return await handleContextualPath(path, track: track, player: player)
    }

    // 2. Has src — single playable track
    if track.src != nil {
      return await handlePlayableTrack(track, player: player)
    }

    // 3. Has path — browsable
    if let path {
      return .browse(path: path)
    }

    // 4. Neither
    return .none
  }

  // MARK: - Private Helpers

  private func handleContextualPath(
    _ path: String,
    track: Track,
    player: some TrackSelectionPlayer,
  ) async -> SelectionResult {
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
        let queue = player.tracks
        let event = TrackLoadEvent(track: track, queue: queue, startIndex: Double(index))
        if await browserManager.awaitTrackLoadHandler(event: event) {
          return .intercepted
        }
        return .play(.skipTo(index: index))
      }
    }

    // Expand the queue from the contextual path
    do {
      if let expanded = try await browserManager.expandQueueFromContextualPath(path) {
        let (tracks, startIndex) = expanded
        let event = TrackLoadEvent(track: track, queue: tracks, startIndex: Double(startIndex))
        if await browserManager.awaitTrackLoadHandler(event: event) {
          return .intercepted
        }
        return .play(.setQueue(tracks: tracks, startIndex: startIndex, sourcePath: parentPath))
      } else {
        // Fallback: single track
        return await singleTrackResult(track)
      }
    } catch {
      logger.error("Error expanding queue: \(error.localizedDescription)")
      // Fallback to single track
      return await singleTrackResult(track)
    }
  }

  private func handlePlayableTrack(
    _ track: Track,
    player _: some TrackSelectionPlayer,
  ) async -> SelectionResult {
    await singleTrackResult(track)
  }

  private func singleTrackResult(_ track: Track) async -> SelectionResult {
    let event = TrackLoadEvent(track: track, queue: [track], startIndex: 0)
    if await browserManager.awaitTrackLoadHandler(event: event) {
      return .intercepted
    }
    return .play(.loadTrack(track))
  }
}
