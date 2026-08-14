/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A view that displays the Now Playing item and controls.
*/
import SwiftUI
import MediaPlayer
import Combine

/// `PlayerController` is iOS player controls.
struct PlayerController: View {
    
    /// The model of the Now Playing item.
    @State var nowPlayingItem: NowPlayingViewModel = NowPlayingViewModel(item: MPMusicPlayerController.applicationMusicPlayer.nowPlayingItem)
    
    /// The artwork to dispay.
    @State var artwork: UIImage = UIImage()
    
    /// A value to determine if the app's music player is playing.
    @State private var isPlaying: Bool = MPMusicPlayerController.applicationMusicPlayer.playbackState == .playing
    
    var body: some View {
        VStack(alignment: .center, spacing: 40) {
            Spacer()
            Image(uiImage: artwork)
            Text(nowPlayingItem.title).font(.title)
            Text(nowPlayingItem.artist).font(.title2)
            HStack(spacing: 40) {
                Button(action: {
                    MPMusicPlayerController.applicationMusicPlayer.skipToPreviousItem()
                }) {
                    Image(systemName: "backward.end")
                        .resizable()
                        .frame(width: 50.0, height: 50.0)
                }
                Button(action: {
                    if MPMusicPlayerController.applicationMusicPlayer.playbackState == .playing {
                        MPMusicPlayerController.applicationMusicPlayer.pause()
                    } else {
                        MPMusicPlayerController.applicationMusicPlayer.play()
                    }
                }) {
                    Image(systemName: isPlaying ? "pause" : "play")
                        .resizable()
                        .frame(width: 50.0, height: 50.0)
                }
                Button(action: {
                    MPMusicPlayerController.applicationMusicPlayer.skipToNextItem()
                }) {
                    Image(systemName: "forward.end")
                        .resizable()
                        .frame(width: 50.0, height: 50.0)
                }
            }
            Spacer()
        }.onReceive(NotificationCenter.default.publisher(for: Notification.Name.MPMusicPlayerControllerPlaybackStateDidChange)) { _ in
            isPlaying = MPMusicPlayerController.applicationMusicPlayer.playbackState == .playing
        }.onReceive(NotificationCenter.default.publisher(for: Notification.Name.MPMusicPlayerControllerNowPlayingItemDidChange)) { _ in
            nowPlayingItem = NowPlayingViewModel(item: MPMusicPlayerController.applicationMusicPlayer.nowPlayingItem)
        }.onReceive(nowPlayingItem.artworkImage, perform: { image in
            artwork = image
        })
    }
}

/// The view model of the Now Playing item.
class NowPlayingViewModel: ObservableObject, Identifiable {
    
    var artist: String = ""
    var title: String = ""
    let artworkImage = PassthroughSubject<UIImage, Never>()
    
    init(item: MPMediaItem?) {
        self.artist = item?.artist ?? item?.albumArtist ?? ""
        self.title = item?.title ?? item?.albumTitle ?? ""
        guard let identifier = item?.playbackStoreID else {
            return
        }
        
        // Get the image and send it to the subscribers.
        let imagePixelSize = CGSize(width: 200, height: 200)
        AppleMusicAPIController.sharedController.fetchUIImageForIdentifier(identifier, ofSize: imagePixelSize, completion: { loadedImage in
            if let image = loadedImage {
                DispatchQueue.main.async {
                    self.artworkImage.send(image)
                }
            }
        })
    }
}
