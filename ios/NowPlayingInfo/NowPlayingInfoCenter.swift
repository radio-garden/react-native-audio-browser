import Foundation
import MediaPlayer

protocol NowPlayingInfoCenter {
  var nowPlayingInfo: [String: Any]? { get set }
}

extension MPNowPlayingInfoCenter: NowPlayingInfoCenter {}
