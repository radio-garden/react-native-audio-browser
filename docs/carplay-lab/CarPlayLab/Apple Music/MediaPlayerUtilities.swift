/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
This file implements a few utilities for interacting with MediaPlayer.
*/
import Foundation
import MediaPlayer

class MediaPlayerUtilities {
    public static let LocalLibraryIdentifierPrefix = "library://"
    
    /// Search for playlists in a user's local library with a predicate.
    class func searchForPlaylistsInLocalLibrary(withPredicate predicate: MPMediaPropertyPredicate?) -> [MPMediaPlaylist]? {
        let mediaQuery = MPMediaQuery.playlists()
        if let predicate = predicate {
            mediaQuery.addFilterPredicate(predicate)
        }
        
        if let items = mediaQuery.collections {
            return items.compactMap { (playlist) -> MPMediaPlaylist? in
                return playlist as? MPMediaPlaylist
            }
        } else {
            return nil
        }
    }

    /// Search for a playlist in a user's local library with a predicate.
    private class func searchForPlaylistInLocalLibrary(withPredicate predicate: MPMediaPropertyPredicate?) -> MPMediaPlaylist? {
        let mediaQuery = MPMediaQuery.playlists()
        if let predicate = predicate {
            mediaQuery.addFilterPredicate(predicate)
        }
        return mediaQuery.collections?.first as? MPMediaPlaylist
    }

    /// Search for a playlist in a user's local library by playlist name.
    class func searchForPlaylistInLocalLibrary(byName playlistName: String) -> MPMediaPlaylist? {
        let predicate = MPMediaPropertyPredicate(value: playlistName, forProperty: MPMediaPlaylistPropertyName)
        return searchForPlaylistInLocalLibrary(withPredicate: predicate)
    }

    /// Search for a playlist in a user's local library by persistent ID.
    class func searchForPlaylistInLocalLibrary(byPersistentID persistentID: UInt64) -> MPMediaPlaylist? {
        let predicate = MPMediaPropertyPredicate(value: persistentID, forProperty: MPMediaPlaylistPropertyPersistentID)
        return searchForPlaylistInLocalLibrary(withPredicate: predicate)
    }
}
