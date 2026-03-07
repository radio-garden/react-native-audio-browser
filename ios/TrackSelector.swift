import Foundation
import os.log

// MARK: - Protocols for testability

@MainActor protocol TrackSelectionBrowser {
  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool
  func expandQueueFromContextualUrl(_ url: String) async throws -> (tracks: [Track], selectedIndex: Int)?
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
  nonisolated(unsafe) private let browserManager: any TrackSelectionBrowser

  nonisolated init(browserManager: any TrackSelectionBrowser) {
    self.browserManager = browserManager
  }

  // MARK: - Result Types

  enum SelectionResult {
    /// Playback action determined and not intercepted by JS handler.
    case play(PlaybackIntent)
    /// JS handleTrackLoad handler intercepted — caller should not play.
    case intercepted
    /// Track is browsable — caller should navigate to this URL.
    case browse(url: String)
    /// Nothing to do (no src, no url).
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
  /// Handles contextual URL expansion, queue reuse, and handler interception.
  func select(
    track: Track,
    player: some TrackSelectionPlayer
  ) async -> SelectionResult {
    let url = track.url

    // 1. Contextual URL (playable-only track with queue context)
    if let url, BrowserPathHelper.isContextual(url) {
      return await handleContextualUrl(url, track: track, player: player)
    }

    // 2. Has src — single playable track
    if track.src != nil {
      return await handlePlayableTrack(track, player: player)
    }

    // 3. Has url — browsable
    if let url {
      return .browse(url: url)
    }

    // 4. Neither
    return .none
  }

  // MARK: - Private Helpers

  private func handleContextualUrl(
    _ url: String,
    track: Track,
    player: some TrackSelectionPlayer
  ) async -> SelectionResult {
    let parentPath = BrowserPathHelper.stripTrackId(url)
    let trackId = BrowserPathHelper.extractTrackId(url)

    // Check if queue already came from this parent path — just skip to the track
    if let trackId,
       parentPath == player.queueSourcePath,
       let index = player.tracks.firstIndex(where: { $0.src == trackId })
    {
      logger.debug("Queue already from \(parentPath), skipping to index \(index)")
      let queue = player.tracks
      let event = TrackLoadEvent(track: track, queue: queue, startIndex: Double(index))
      if await browserManager.awaitTrackLoadHandler(event: event) {
        return .intercepted
      }
      return .play(.skipTo(index: index))
    }

    // Expand the queue from the contextual URL
    do {
      if let expanded = try await browserManager.expandQueueFromContextualUrl(url) {
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
    player: some TrackSelectionPlayer
  ) async -> SelectionResult {
    return await singleTrackResult(track)
  }

  private func singleTrackResult(_ track: Track) async -> SelectionResult {
    let event = TrackLoadEvent(track: track, queue: [track], startIndex: 0)
    if await browserManager.awaitTrackLoadHandler(event: event) {
      return .intercepted
    }
    return .play(.loadTrack(track))
  }
}
