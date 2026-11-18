import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

public enum TrackPlayerError: Error {
  public enum PlaybackError: Error, Equatable {
    case failedToLoadKeyValue
    case invalidSourceUrl(String)
    case notConnectedToInternet
    /// A transport failure: DNS, connect, TLS, timeout, or a dropped connection, which can come mid-stream after audio has played.
    case hostUnreachable
    case httpStatus(Int)
    case playbackFailed
    case trackWasUnplayable
    case playbackStalled
  }

  public enum QueueError: Error {
    case noCurrentItem
    case invalidIndex(index: Int, message: String)
    case empty
    case invalidTrack(title: String)
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
    case .hostUnreachable:
      "Could not reach the stream host"
    case let .httpStatus(status):
      "Server responded with HTTP \(status)"
    case .playbackFailed:
      "Playback failed"
    case .trackWasUnplayable:
      "Track is not playable"
    case .playbackStalled:
      "Playback stalled"
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
    case let .invalidTrack(title):
      "Track must have either path or src defined. Track: title='\(title)'"
    }
  }
}

// MARK: - Cross-platform Classification

public extension TrackPlayerError.PlaybackError {
  /// For diagnostics and telemetry only; consumers branch on `kind`.
  var code: String {
    switch self {
    case .failedToLoadKeyValue: "failed-to-load"
    case .invalidSourceUrl: "invalid-source-url"
    case .notConnectedToInternet: "not-connected-to-internet"
    case .hostUnreachable: "host-unreachable"
    case .httpStatus: "http-status"
    case .playbackFailed: "playback-failed"
    case .trackWasUnplayable: "track-unplayable"
    case .playbackStalled: "playback-stalled"
    }
  }

  /// Android maps its ExoPlayer errors onto the same kinds; keep the two in step.
  var kind: PlaybackErrorKind {
    switch self {
    case .notConnectedToInternet: .offline
    case .hostUnreachable: .unreachable
    case let .httpStatus(status): TrackPlayerError.PlaybackError.kind(forHttpStatus: status)
    // To the listener a missing or malformed URL is as broken as an undecodable stream.
    case .invalidSourceUrl, .trackWasUnplayable: .unplayable
    case .playbackStalled: .stalled
    case .failedToLoadKeyValue, .playbackFailed: .unknown
    }
  }

  var statusCode: Int? {
    if case let .httpStatus(status) = self { return status }
    return nil
  }

  static func kind(forHttpStatus status: Int) -> PlaybackErrorKind {
    switch status {
    case 404, 410: .notFound
    case 500 ... 599: .serverError
    // Auth, geo-blocking, rate limits: the stream is refused, not worth retrying.
    case 400 ... 499: .rejected
    default: .unknown
    }
  }
}

// MARK: - Nitro PlaybackError Conversion

extension TrackPlayerError.PlaybackError {
  /// `retrying` marks an error the retry loop is still working on; a terminal
  /// error carries nil, not false.
  ///
  /// Not behind `#if canImport(NitroModules)`: the stub in `NitroTypeStubs` mirrors
  /// the generated struct, so the tests exercise this converter.
  func toNitroError(retrying: Bool = false) -> PlaybackError {
    PlaybackError(
      kind: kind,
      code: code,
      message: errorDescription ?? "Unknown error",
      statusCode: statusCode.map(Double.init),
      retrying: retrying ? true : nil,
    )
  }
}
