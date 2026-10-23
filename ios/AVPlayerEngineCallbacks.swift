import AVFoundation
import MediaPlayer
import NitroModules

/// What the AVPlayerEngine reports: the remote commands, and the metadata and now-playing
/// changes, which carry AVFoundation types. HybridAudioBrowser implements it. The method names
/// are prefixed so they don't collide with the JS callback properties.
@MainActor
protocol AVPlayerEngineCallbacks: RemoteCommandCallbacks {
  func playerDidReceiveCommonMetadata(_ metadata: [AVMetadataItem])
  func playerDidReceiveTimedMetadata(_ metadata: [AVTimedMetadataGroup])
  func playerDidReceiveChapterMetadata(_ metadata: [AVTimedMetadataGroup])
  /// The now-playing lines or track changed; carries the metadata JS sees.
  func playerDidChangeNowPlaying(_ metadata: NowPlayingMetadata)
}
