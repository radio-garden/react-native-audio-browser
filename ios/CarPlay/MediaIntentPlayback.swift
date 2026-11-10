import Foundation
import NitroModules
import os.log

private let logger = Logger(subsystem: "com.audiobrowser", category: "MediaIntentPlayback")

/// Plays a Siri play-media intent through the single voice play funnel (ADR 0002): resume, or
/// search, queue and play, then ask for Now Playing.
enum MediaIntentPlayback {
  /// Callable before React Native has booted: waits, within the assistant's time budget, for the
  /// player and a configured browser. A JS reload or a second setup can replace the bridge during
  /// any await, so the player is looked up again after one and the intent fails once the bridge is
  /// gone. Returns whether playback was requested; true does not mean audio has started, and a
  /// failed cold resume still returns true.
  @MainActor
  static func play(criteria: MediaIntentCriteria) async -> Bool {
    guard let (browser, player) = await playerAndConfiguredBrowser.wait(timeout: .seconds(8)) else {
      logger.error("browser/player not ready within budget")
      return false
    }
    // Resume is not gated: the gate blocks finding content, never hearing the current or
    // persisted track.
    if criteria.isResume {
      if player.currentTrack != nil {
        logger.info("resume: warm — playing current track")
        player.play()
      } else {
        guard let session = player.persistedSession() else { return false }
        var expanded: (tracks: [Track], selectedIndex: Int)?
        if let path = session.track.path {
          expanded = try? await browser.browserManager.expandQueueFromContextualPath(path)
        }
        guard let player = livePlayer(of: browser) else { return false }
        player.resume(session, queue: expanded)
      }
      browser.showNowPlayingRequestedEmitter.emit(())
      return true
    }

    // The search API still text-searches `q` only; the structured fields are forward-compatible,
    // as on Android.
    let params = SearchParams(
      mode: criteria.searchMode.flatMap { SearchMode(fromString: $0) },
      query: criteria.query,
      genre: criteria.genre,
      artist: criteria.artist,
      album: criteria.album,
      title: criteria.title,
      playlist: criteria.playlist,
      reference: criteria.reference == .my ? .my : .unknown,
    )
    guard await browser.browseGate.decide(
      NativeGateRequest(reason: .search, path: nil, search: params),
      report: { browser.onGate($0) },
    ) == nil else {
      logger.info("search refused — gated")
      return false
    }
    do {
      guard let tracks = try await browser.browserManager.searchPlayable(params),
            let player = livePlayer(of: browser) else { return false }
      try player.setQueue(tracks, initialIndex: 0, playWhenReady: true)
      browser.showNowPlayingRequestedEmitter.emit(())
      return true
    } catch {
      logger.error("search failed: \(error.localizedDescription)")
      return false
    }
  }

  /// `browser`'s player while `browser` is still the live bridge; a replaced bridge keeps its
  /// destroyed player.
  @MainActor
  private static func livePlayer(of browser: HybridAudioBrowser) -> Player? {
    HybridAudioBrowser.shared === browser ? browser.player : nil
  }
}
