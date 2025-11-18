# TODO

## Browser Configuration

- [ ] Consider renaming `play` property in `BrowserConfiguration` to better describe what it does (controls queue behavior when navigating to/selecting a track)
- [ ] Unified browse configuration - consolidate `routes` + `browse` into a single concept

## Android / Caching

- [ ] Replace basic mutableMap caching in `BrowserManager` with proper LRU cache

## Android / MediaSession

- [ ] Implement playback resumption in `MediaSessionCallback.onPlaybackResumption()`:
  - [ ] add some kind of configuration to BrowserConfiguration to handle playback
        resumption, i.e. returning the most recently played queue
  - [ ] Return stored `MediaItemsWithStartPosition` to restore playback state

- [ ] Android Auto cold start: When Android Auto launches the app before React Native has loaded, it requests media browsing before the browser is registered, causing empty content.
  - [ ] Implement wait mechanism for all accesses to `browser?.browserManager`:
    - [ ] Wait for browser registration with timeout (e.g., 5-10 seconds)
    - [ ] Applies to: `MediaSessionCallback` (browsing, search), `Player.playFromSearch()` (voice commands), `Player.browser` setter (MediaSession command updates)
    - [ ] Consider using a `CompletableDeferred<AudioBrowser>` or similar pattern
    - [ ] Gracefully handle timeout by returning empty results or error

## Voice Search / MEDIA_PLAY_FROM_SEARCH

- [x] Basic voice search implementation (unstructured query only)
  - [x] Handle `MEDIA_PLAY_FROM_SEARCH` intent in `Service.onStartCommand()`
  - [x] Extract query from `SearchManager.QUERY` extra
  - [x] Implement `Player.playFromSearch()` to execute search and play results
  - [x] Implement `BrowserManager.searchPlayable()` to handle browsable vs playable results
  - [ ] Silent failure in voice search (Service.kt:92-106):
    - Issue: The boolean return from `playFromSearch()` is ignored - user gets no feedback if search fails
    - Recommendation: Log the result or show notification/toast when search fails (e.g., "No results found for 'query'")

- [x] Full MEDIA_PLAY_FROM_SEARCH implementation with search modes:
  - [x] Parse `MediaStore.EXTRA_MEDIA_FOCUS` to determine search mode
  - [x] Support structured search modes:
    - [x] Any (`"vnd.android.cursor.item/*"` with empty query) - play last playlist or smart choice
    - [x] Unstructured (mode=null) - plain text search when no specific structure detected
    - [x] Genre (`Audio.Genres.ENTRY_CONTENT_TYPE`) - extract genre from `"android.intent.extra.genre"`
    - [x] Artist (`Audio.Artists.ENTRY_CONTENT_TYPE`) - extract artist from `MediaStore.EXTRA_MEDIA_ARTIST`
    - [x] Album (`Audio.Albums.ENTRY_CONTENT_TYPE`) - extract album from `MediaStore.EXTRA_MEDIA_ALBUM`
    - [x] Song (`"vnd.android.cursor.item/audio"`) - extract title from `MediaStore.EXTRA_MEDIA_TITLE`
    - [x] Playlist (`Audio.Playlists.ENTRY_CONTENT_TYPE`) - extract playlist from `"android.intent.extra.playlist"`
  - [x] Pass structured search parameters to `BrowserConfiguration.search`:
    - [x] Updated `SearchSource` callback signature to accept `SearchParams` with nullable mode and structured fields
    - [x] Updated TypeScript interface and Kotlin implementation
    - [x] Callback receives full SearchParams object; API config gets all params in query string
    - [x] mode is nullable - null indicates unstructured search, non-null indicates structured search type
  - [ ] Test with voice commands like "play michael jackson billie jean"
