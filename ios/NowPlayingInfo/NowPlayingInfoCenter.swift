import Foundation
import MediaPlayer

protocol NowPlayingInfoCenter {
  var nowPlayingInfo: [String: Any]? { get set }
  var playbackState: MPNowPlayingPlaybackState { get set }
}

extension MPNowPlayingInfoCenter: NowPlayingInfoCenter {}
