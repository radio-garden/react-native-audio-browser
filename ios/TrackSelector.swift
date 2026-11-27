import Foundation
import os.log

// MARK: - Protocols for testability

@MainActor protocol TrackSelectionBrowser {
  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool
  func expandQueueFromContextualPath(_ path: String) async throws
    -> (tracks: [Track], selectedIndex: Int, sourcePath: String)?
}

@MainActor protocol TrackSelectionPlayer {
  var tracks: [Track] { get }
  var queueSourcePath: String? { get }
  /// -1 when nothing is current.
  var currentIndex: Int { get }
}

/// Decides what selecting a track does, for both HybridAudioBrowser and CarPlaySession;
/// each caller carries out the returned `SelectionResult` its own way.
@MainActor
class TrackSelector {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "TrackSelector")
  private nonisolated(unsafe) let browserManager: any TrackSelectionBrowser
  /// Bumped by every selection that would play, whether or not the app's `handleTrackLoad`
  /// intercepts it; one that finds it bumped has been superseded.
  private var latestSelection: UInt = 0

  nonisolated init(browserManager: any TrackSelectionBrowser) {
    self.browserManager = browserManager
  }

  // MARK: - Result Types

  enum SelectionResult {
    case play(PlaybackIntent)
    /// The app's `handleTrackLoad` took the selection; the caller plays nothing.
    case intercepted
    /// The track has no `src`; the caller navigates to `path`.
    case browse(path: String)
    /// A later selection began to play before this one resolved; the caller plays nothing.
    case superseded
    case none
  }

  enum PlaybackIntent {
    case skipTo(index: Int)
    case setQueue(tracks: [Track], startIndex: Int, sourcePath: String?)
    case loadTrack(Track)
  }

  // MARK: - Selection

  /// Resolves a selection: nothing for a disabled track; for a contextual path, a skip within
  /// the current queue when it came from the same parent, else the parent expanded into a queue
  /// (the track alone if expansion fails); a single track for a `src`; browse for a bare `path`.
  /// A playback is first offered to the app's `handleTrackLoad`, which can intercept it.
  /// Of the playbacks still resolving, only the latest plays, even when the app intercepts it:
  /// an earlier one is `superseded`, and is not offered to the app once it is.
  func select(
    track: Track,
    player: some TrackSelectionPlayer,
  ) async -> SelectionResult {
    // Checked here, not only in the UI: a stale resume path can still deliver a disabled track.
    if track.disabled == true {
      return .none
    }

    let contextualPath = track.path.flatMap { BrowserPathHelper.isContextual($0) ? $0 : nil }
    if contextualPath == nil, track.src == nil {
      if let path = track.path { return .browse(path: path) }
      return .none
    }

    let selection = beginSelection()
    let playback: PlannedPlayback = if let contextualPath {
      await contextualPlayback(contextualPath, track: track, player: player)
    } else {
      .single(track)
    }
    guard isLatest(selection) else { return .superseded }

    let event = TrackLoadEvent(
      track: track,
      queue: playback.queue,
      startIndex: Double(playback.index),
      startPosition: nil,
    )
    if await browserManager.awaitTrackLoadHandler(event: event) {
      return .intercepted
    }
    guard isLatest(selection) else { return .superseded }
    return .play(playback.intent)
  }

  // MARK: - Private Helpers

  private func beginSelection() -> UInt {
    latestSelection += 1
    return latestSelection
  }

  private func isLatest(_ selection: UInt) -> Bool {
    selection == latestSelection
  }

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

    // Exact path first: its `__index` pins the tapped copy when the page holds the same identity
    // more than once. Identity matching is for index-less paths (e.g. from persisted state
    // without one) and for the playing track, which the page may have moved; any other indexed
    // path with no exact match expands instead, re-scoping the queue to the tapped section.
    if parentPath == player.queueSourcePath {
      let tracks = player.tracks
      var skipIndex = tracks.firstIndex(where: { $0.path == path })
      if skipIndex == nil, let trackId {
        if BrowserPathHelper.extractIndex(path) == nil {
          skipIndex = tracks.firstIndex(where: { $0.identity == trackId })
        } else if tracks.indices.contains(player.currentIndex), tracks[player.currentIndex].identity == trackId {
          skipIndex = player.currentIndex
        }
      }
      if let index = skipIndex {
        logger.debug("Queue already from \(parentPath), skipping to index \(index)")
        return PlannedPlayback(queue: tracks, index: index, intent: .skipTo(index: index))
      }
    }

    do {
      guard let (tracks, startIndex, sourcePath) = try await browserManager.expandQueueFromContextualPath(path) else {
        return .single(track)
      }
      return PlannedPlayback(
        queue: tracks, index: startIndex,
        intent: .setQueue(tracks: tracks, startIndex: startIndex, sourcePath: sourcePath),
      )
    } catch {
      logger.error("Error expanding queue: \(error.localizedDescription)")
      return .single(track)
    }
  }
}
