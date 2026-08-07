import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

public enum TrackPlayerError: Error {
  public enum PlaybackError: Error, Equatable {
    case failedToLoadKeyValue
    case invalidSourceUrl(String)
    case notConnectedToInternet
    /// DNS, connect, timeout or a dropped connection — we never reached the host.
    case hostUnreachable
    /// The server answered with a non-2xx status.
    case httpStatus(Int)
    case playbackFailed
    case trackWasUnplayable
    case playbackStalled
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
    }
  }
}

// MARK: - Cross-platform Classification

public extension TrackPlayerError.PlaybackError {
  /// The platform-specific identifier, for diagnostics only. Consumers branch
  /// on `kind`; this is what they log.
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

  /// The normalized classification handed to JS. Android derives the same set
  /// from its ExoPlayer codes, so app-side copy can switch on this alone.
  var kind: PlaybackErrorKind {
    switch self {
    case .notConnectedToInternet: .offline
    case .hostUnreachable: .unreachable
    case let .httpStatus(status): TrackPlayerError.PlaybackError.kind(forHttpStatus: status)
    // A missing or malformed source URL is a broken stream from the listener's
    // side — same outcome as an undecodable one.
    case .invalidSourceUrl, .trackWasUnplayable: .unplayable
    case .playbackStalled: .stalled
    // Context fallbacks: the underlying error carried nothing we could classify.
    case .failedToLoadKeyValue, .playbackFailed: .unknown
    }
  }

  /// The HTTP status behind this error, when it came from a server response.
  var statusCode: Int? {
    if case let .httpStatus(status) = self { return status }
    return nil
  }

  static func kind(forHttpStatus status: Int) -> PlaybackErrorKind {
    switch status {
    case 404, 410: .notFound
    case 500...599: .serverError
    // Every other 4xx is the server refusing us — auth, geo-blocking, a
    // rate limit. All of them mean "you can't have this stream", not "retry".
    case 400...499: .rejected
    default: .unknown
    }
  }
}

// MARK: - Nitro PlaybackError Conversion

// Internal, not public: the stub `PlaybackError` struct is internal, and every
// caller of this lives in the module.
extension TrackPlayerError.PlaybackError {
  /// Converts to Nitro PlaybackError for JS callbacks.
  ///
  /// Deliberately not behind `#if canImport(NitroModules)`: the stub struct in
  /// `NitroTypeStubs` mirrors the generated one field for field, so this single
  /// definition compiles in both worlds and the tests exercise the shipped
  /// converter rather than a copy of it.
  /// `retrying` marks an advisory error the retry loop is still working on
  /// (surfaced with a non-terminal playback state); terminal errors leave it nil.
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
