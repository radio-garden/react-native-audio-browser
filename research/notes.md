## Android Auto Callbacks

### MediaLibraryService
Media apps often contain collections of media items, organized in a hierarchy. For example, songs in an album or TV episodes in a playlist. This hierarchy of media items is known as a media library.

The MediaLibraryService API expects your media library to be structured in a tree format, with a single root node and children nodes that may be playable or further browsable.

- onGetItem(): Called when a MediaBrowser requests a MediaItem by getItem.
- onGetSession(): If desired, validate the controller before returning the media library session
- onGetLibraryRoot() for when a client requests the root MediaItem of a content tree
- onGetChildren() for when a client requests the children of a MediaItem in the content tree

Seemingly pagination is ignored by android auto, so questionable if we need to support it: https://stackoverflow.com/questions/74057148/how-does-android-auto-pagination-work-with-media3

- onDestroy: release the player and media library session
- onSubscribe: Called when a MediaBrowser subscribes to the given parent id by subscribe.
https://github.com/androidx/media/issues/561 -> propose to always return true, to allow MediaLibraryService.MediaLibrarySession::notifyChildrenChanged to cause a refresh
- onUnsubscribe: Called when a MediaBrowser unsubscribes from the given parent ID by unsubscribe.
- onSetMediaItems():
  - lets you define the start item and position in the playlist. For example, you can expand a single requested item to an entire playlist and instruct the player to start at the index of the originally requested item.
  - used for voice search -> single item in mediaItems and mediaItems[0].requestMetadata.searchQuery is not null
- onSearch: Called when a MediaBrowser requests a search with search.
  - Performs full search internally, but only sends count via `notifySearchResultChanged()`
  - Two-phase: onSearch (search + count) → notifySearchResultChanged → onGetSearchResult (search again + return items)
  - Optimization: Cache last search as `Pair<String, List<MediaItem>>`, check `.first` for query equality
- onGetSearchResult() for when a client requests search results from the content tree for a given query
  - Returns actual MediaItems with pagination support
- onCastSessionAvailable(): Called when a cast session becomes available to the player.
- onCastSessionUnavailable(): Called when the cast session becomes unavailable.

#### Manifest file

```
<service
    android:name=".PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService"/>
        <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
</service>

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```
