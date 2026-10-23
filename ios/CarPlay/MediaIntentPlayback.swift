import Foundation
import NitroModules
import os.log

private let logger = Logger(subsystem: "com.audiobrowser", category: "MediaIntentPlayback")

/// Plays a Siri play-media intent through the single voice play funnel (ADR 0002): resume, or
/// search → queue → play, then surface Now Playing.
enum MediaIntentPlayback {
  /// Callable before the bridge exists: it waits, within the assistant's budget, for the player
  /// and a configured browser, which come up once React Native boots. Each await may outlive the
  /// bridge or its player (a JS reload or a second setup replaces them), so the player is looked
  /// up again after one, and the intent fails once the bridge is gone.
  @MainActor
  static func play(criteria: MediaIntentCriteria) async -> Bool {
    guard let (browser, player) = await playerAndConfiguredBrowser.wait(timeout: .seconds(8)) else {
      logger.error("browser/player not ready within budget")
      return false
    }
    // Resume is not gated: the gate blocks finding content, never hearing the active or
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

    // The API still text-searches `q`; the structured fields are forward-compatible, as on
    // Android.
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
    guard await browser.browseGate.decide(NativeGateRequest(reason: .search, path: nil, search: params)) == nil else {
      logger.info("search refused — gated")
      browser.onGate(GateEvent(reason: .search))
      return false
    }
    do {
      guard let tracks = try await browser.browserManager.searchPlayable(params),
            let player = livePlayer(of: browser) else { return false }
      player.setQueue(tracks, initialIndex: 0, playWhenReady: true)
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
