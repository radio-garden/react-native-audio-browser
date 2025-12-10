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
      "Failed to load audio track"
    case let .invalidSourceUrl(url):
      "Invalid audio source URL: \(url)"
    case .notConnectedToInternet:
      "No internet connection"
    case .playbackFailed:
      "Playback failed"
    case .trackWasUnplayable:
      "Track is not playable"
    }
  }
}

extension TrackPlayerError.QueueError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .noCurrentItem:
      "No current track"
    case let .invalidIndex(index, message):
      "Invalid track index \(index): \(message)"
    case .empty:
      "Queue is empty"
    }
  }
}

// MARK: - Nitro PlaybackError Conversion

public extension TrackPlayerError.PlaybackError {
  /// Converts to Nitro PlaybackError for JS callbacks
  func toNitroError() -> PlaybackError {
    let code = switch self {
    case .failedToLoadKeyValue:
      "failed_to_load"
    case .invalidSourceUrl:
      "invalid_source_url"
    case .notConnectedToInternet:
      "not_connected_to_internet"
    case .playbackFailed:
      "playback_failed"
    case .trackWasUnplayable:
      "track_unplayable"
    }
    return PlaybackError(code: code, message: errorDescription ?? "Unknown error")
  }
}
