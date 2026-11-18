/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
`AppDelegate` is the `UIApplicationDelegate`.
*/

import UIKit
import Intents
import os.log
import MediaPlayer
import CoreSpotlight

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    
    // MARK: UIApplicationDelegate

    func applicationDidBecomeActive(_ application: UIApplication) {
        MemoryLogger.shared.appendEvent("Application did become active.")
    }

    func applicationWillResignActive(_ application: UIApplication) {
        MemoryLogger.shared.appendEvent("Application will resign active.")
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        MemoryLogger.shared.appendEvent("Application did enter background.")
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        MemoryLogger.shared.appendEvent("Application will enter foreground.")
    }
    
    func applicationWillTerminate(_ application: UIApplication) {
        MPMusicPlayerController.applicationQueuePlayer.endGeneratingPlaybackNotifications()
    }
    
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        
        // Let the system know the app can be an option for playing Music.
        DispatchQueue.global(qos: .userInitiated).async {
            /// - Tag: register
            let context = INMediaUserContext()
            context.numberOfLibraryItems = MPMediaQuery.songs().items?.count
            AppleMusicAPIController.sharedController.prepareForRequests { (success) in
                if success {
                    context.subscriptionStatus = .subscribed
                } else {
                    context.subscriptionStatus = .notSubscribed
                }
                context.becomeCurrent()
            }
        }
        return true
    }

    func application(_ application: UIApplication, handle intent: INIntent, completionHandler: @escaping (INIntentResponse) -> Void) {
        if let playMediaIntent = intent as? INPlayMediaIntent {
            handlePlayMediaIntent(playMediaIntent, completion: completionHandler)
        } else if let customPlaylistIntent = intent as? CustomPlaylistIntent {
            handleCustomPlaylistIntent(customPlaylistIntent, completion: completionHandler)
        } else {
            fatalError("Unknown Intent")
        }
    }
    
}

extension AppDelegate: CustomPlaylistIntentHandling {
    
    func resolveTerm(for intent: CustomPlaylistIntent, with completion: @escaping ([INStringResolutionResult]) -> Void) {
        if let terms = intent.term, !terms.isEmpty {
            completion([INStringResolutionResult.success(with: terms.joined(separator: ", "))])
        } else {
            completion([INStringResolutionResult.needsValue()])
        }
    }
    
    func handle(intent: CustomPlaylistIntent, completion: @escaping (CustomPlaylistIntentResponse) -> Void) {
        handleCustomPlaylistIntent(intent, completion: completion)
    }
    
}

extension AppDelegate {
    
    func handleCustomPlaylistIntent(_ intent: CustomPlaylistIntent, completion: @escaping (CustomPlaylistIntentResponse) -> Void) {
        AppleMusicAPIController.sharedController.prepareForRequests { (done) in
            AppleMusicAPIController.sharedController.searchForTerm(intent.term?.joined(separator: ", ")) { items in
                if let items = items {
                    MemoryLogger.shared.appendEvent("Song count \(items.count).")
                    AppleMusicAPIController.playWithItems(items: items.compactMap({ (song) -> String in
                        return song.identifier
                    }))
                    completion(CustomPlaylistIntentResponse(code: .success, userActivity: nil))
                } else {
                    MemoryLogger.shared.appendEvent("Song count 0.")
                    completion(CustomPlaylistIntentResponse(code: .failure, userActivity: nil))
                }
            }
        }
    }
    
    func handlePlayMediaIntent(_ intent: INPlayMediaIntent,
                               completion: @escaping (INPlayMediaIntentResponse) -> Void) {
        // Extract the first media item from the intent's media items (these will have been resolved in the extension).
        guard let mediaItem = intent.mediaItems?.first, let identifier = mediaItem.identifier else {
            MemoryLogger.shared.appendEvent("INPlayMediaIntent found no media items.")
            return
        }
        
        registerForSpotlight(mediaItem: mediaItem, identifier: identifier)
        
        MPMusicPlayerController.applicationMusicPlayer.pause()
        MPMusicPlayerController.applicationMusicPlayer.prepareToPlay { error in
            if let error = error {
                MemoryLogger.shared.appendEvent("prepareToPlay error: \(error.localizedDescription).")
                completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            } else {
                DispatchQueue.main.async {
                    MPMusicPlayerController.applicationMusicPlayer.play()
                }
                // Donate an interaction to the system.
                let response = INPlayMediaIntentResponse(code: .success, userActivity: nil)
                let interaction = INInteraction(intent: intent, response: response)
                interaction.donate(completion: nil)
                completion(response)
            }
        }
    }
    
    /// Registers the app for CoreSpotlight according to the current selection.
    private func registerForSpotlight(mediaItem: INMediaItem, identifier: String) {
        /// Get the appropriate domain for Spotlight search.
        func domainForType(type: INMediaItemType) -> String {
            return type == .playlist ? "MPMediaGrouping.playlist" :
            type == .album ? "MPMediaGrouping.album" : "MPMediaGrouping.song"
        }
        
        if mediaItem.type == .playlist, let range = identifier.range(of: MediaPlayerUtilities.LocalLibraryIdentifierPrefix) {
            // Extract the persistentID for the local playlist and look it up in the library.
            guard let persistentID = UInt64(identifier[range.upperBound...]),
                let playlist = MediaPlayerUtilities.searchForPlaylistInLocalLibrary(byPersistentID: persistentID) else {
                MemoryLogger.shared.appendEvent("INPlayMediaIntent found no playlist.")
                return
            }
            // Set the player queue to the local playlist.
            MPMusicPlayerController.applicationMusicPlayer.setQueue(with: playlist)
            // Add the items from the Playlist to Spotlight Search so users can directly play the songs with the app.
            CSSearchableIndex.default().indexSearchableItems(playlist.items.compactMap({ (playlistItem) -> CSSearchableItem? in
                let attributes = CSSearchableItemAttributeSet(contentType: .audio)
                attributes.title = playlistItem.title
                attributes.artist = playlistItem.artist
                attributes.album = playlistItem.albumTitle
                attributes.genre = playlistItem.genre
                attributes.playCount = NSNumber(value: playlistItem.playCount)
                attributes.lastUsedDate = playlistItem.lastPlayedDate
                return CSSearchableItem(uniqueIdentifier: identifier, domainIdentifier: domainForType(type: mediaItem.type), attributeSet: attributes)
            }), completionHandler: nil)
        } else {
            // Reset the player queue to the store identifier; this could be a song, album or playlist.
            MPMusicPlayerController.applicationMusicPlayer.setQueue(with: [identifier])
            // Add the item from the Playlist to Spotlight Search so user's can directly play the songs with the app.
            let attributes = CSSearchableItemAttributeSet(contentType: .audio)
            attributes.title = mediaItem.title
            attributes.artist = mediaItem.artist
            let item = CSSearchableItem(uniqueIdentifier: identifier, domainIdentifier: domainForType(type: mediaItem.type), attributeSet: attributes)
            CSSearchableIndex.default().indexSearchableItems([item], completionHandler: nil)
        }
    }
    
}
