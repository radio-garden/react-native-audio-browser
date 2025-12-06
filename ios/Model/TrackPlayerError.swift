import Foundation
import NitroModules

public enum TrackPlayerError: Error {
  public enum PlaybackError: Error, Equatable {
    case failedToLoadKeyValue
    case invalidSourceUrl(String)
    case notConnectedToInternet
    case playbackFailed
    case trackWasUnplayable
  }

  public enum QueueError: Error {
    case noCurrentItem
    case invalidIndex(index: Int, message: String)
    case empty
  }
}

extension TrackPlayerError.PlaybackError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .failedToLoadKeyValue:
      return "Failed to load audio track"
    case let .invalidSourceUrl(url):
      return "Invalid audio source URL: \(url)"
    case .notConnectedToInternet:
      return "No internet connection"
    case .playbackFailed:
      return "Playback failed"
    case .trackWasUnplayable:
      return "Track is not playable"
    }
  }
}

extension TrackPlayerError.QueueError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .noCurrentItem:
      return "No current track"
    case let .invalidIndex(index, message):
      return "Invalid track index \(index): \(message)"
    case .empty:
      return "Queue is empty"
    }
  }
}

// MARK: - Nitro PlaybackError Conversion

extension TrackPlayerError.PlaybackError {
  /// Converts to Nitro PlaybackError for JS callbacks
  public func toNitroError() -> PlaybackError {
    let code: String
    switch self {
    case .failedToLoadKeyValue:
      code = "failed_to_load"
    case .invalidSourceUrl:
      code = "invalid_source_url"
    case .notConnectedToInternet:
      code = "not_connected_to_internet"
    case .playbackFailed:
      code = "playback_failed"
    case .trackWasUnplayable:
      code = "track_unplayable"
    }
    return PlaybackError(code: code, message: errorDescription ?? "Unknown error")
  }
}
