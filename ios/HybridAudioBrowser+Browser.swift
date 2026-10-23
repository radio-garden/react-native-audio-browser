import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  /// Navigates the in-app browser, reporting a failure as the navigation error. A clear is
  /// synchronous: JS hears it before the navigation that may set the error again.
  internal func navigate(_ path: String, clearError: Bool) {
    if clearError { onMainActor { navigationError.clear() } }
    Task { @MainActor in
      do {
        try await browserManager.load(path)
      } catch {
        await reportNavigationError(error, path: path)
      }
    }
  }

  @MainActor
  private func reportNavigationError(_ error: Error, path: String) async {
    await navigationError.report(NavigationError.from(error)) { [browserManager] in
      await browserManager.formatted($0, path: path)
    }
  }

  // MARK: - Browser Methods

  func navigatePath(path: String) throws {
    navigate(path, clearError: true)
  }

  func navigateTrack(track: Track) throws {
    // @MainActor: `player` is main-confined, and `trackSelector.select` is
    // MainActor anyway — a bare Task read the reference off the cooperative
    // pool, racing the assignment in `setupPlayer`.
    Task { @MainActor in
      guard let player else { return }
      let result = await trackSelector.select(track: track, player: player)
      switch result {
      case let .play(intent):
        player.execute(intent)
      case .intercepted:
        break
      case let .browse(path):
        navigate(path, clearError: true)
      case .none:
        break
      }
    }
  }

  func search(query: String) throws -> Promise<[Track]> {
    Promise.async { [weak self] in
      guard let self else { return [] }
      let resolved = try await browserManager.search(query)
      return resolved.flattenedChildren ?? []
    }
  }

  func getContent() throws -> ResolvedTrack? {
    onMainActor { browserManager.content }
  }

  func getNavigationError() throws -> NavigationError? {
    onMainActor { navigationError.error }
  }

  func getFormattedNavigationError() throws -> FormattedNavigationError? {
    onMainActor { navigationError.formatted }
  }

  /// Internal signal sent on `externalContentChangedEmitter` to tell CarPlay to
  /// refresh every displayed template. Not a real path (paths are always
  /// `/…`), so it can't collide with one; kept internal — the public API is
  /// `invalidateAllContent()`.
  internal static let invalidateAllSentinel = "__rnab_invalidate_all__"

  func notifyContentChanged(path: String) throws {
    // Throws on a contextual path (matching Android's `require`) — the caller
    // must pass the container path, or the invalidation silently hits nothing.
    try onMainActor { try browserManager.invalidateContentCache(path) }

    // Notify external controllers (CarPlay) that content changed
    externalContentChangedEmitter.emit(path)

    // Re-resolve the path if it's the current browser path
    if onMainActor({ browserManager.path }) == path {
      navigate(path, clearError: false)
    }
  }

  func invalidateAllContent() throws {
    onMainActor {
      browserManager.invalidateAll { error, path in
        await self.reportNavigationError(error, path: path)
      }
    }
    // Tell external controllers (CarPlay) to refresh every displayed template.
    externalContentChangedEmitter.emit(Self.invalidateAllSentinel)
  }

  func setFavorites(favorites: [String]) throws {
    onMainActor { browserManager.setFavorites(favorites) }
  }

  // MARK: - Gate

  func setGate(gate: Gate?, hasResolver: Bool) throws {
    onMainActor { browseGate.set(chrome: gate, hasResolver: hasResolver) }
  }

  func clearGate() throws {
    onMainActor { browseGate.clear() }
  }

  // MARK: - Car Connection (CarPlay)

  /// Static because the CarPlay scene can connect before the JS runtime
  /// creates the shared instance (cold start in the car).
  internal nonisolated(unsafe) static var carPlayConnected = false

  /// Called by the CarPlay scene delegate (via `RNABCarPlayController`) on
  /// scene connect/disconnect — not from controller start/stop, which also
  /// cycle on a JS reload and would emit spurious connection changes.
  internal static func setCarPlayConnected(_ connected: Bool) {
    guard carPlayConnected != connected else { return }
    carPlayConnected = connected
    shared?.onCarConnectedChanged(connected)
  }

  func isCarConnected() throws -> Bool {
    HybridAudioBrowser.carPlayConnected
  }
}
