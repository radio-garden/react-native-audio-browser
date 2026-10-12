import Foundation
import os.log

private let intentLogger = Logger(subsystem: "com.audiobrowser", category: "MediaIntent")

extension HybridAudioBrowser {
  /// Sets the favorite state of the playing track for a Siri intent, through the same path as
  /// the CarPlay favorite button. False when nothing is playing, the playing track has no
  /// identity to favorite, or the change throws.
  @MainActor
  static func favoriteCurrentTrack(_ favorited: Bool) -> Bool {
    guard let browser = shared else { return false }
    do {
      return try browser.favoriteActiveTrack(favorited)
    } catch {
      intentLogger.error("favoriteCurrentTrack(\(favorited)) failed: \(error.localizedDescription)")
      return false
    }
  }
}
