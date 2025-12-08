package com.audiobrowser.browser

import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.audiobrowser.http.HttpClient
import com.audiobrowser.http.RequestConfigBuilder
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Core browser manager that handles navigation, search, and media browsing.
 *
 * This class contains the main business logic for:
 * - Route resolution and path matching with parameter extraction
 * - HTTP API requests and response processing
 * - JavaScript callback invocation
 * - Fallback handling and error management
 */
class BrowserManager {
  private val router = SimpleRouter()
  private val httpClient = HttpClient()
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private var onPathChanged: ((String) -> Unit)? = null
  private var onContentChanged: ((ResolvedTrack?) -> Unit)? = null
  private var onTabsChanged: ((Array<Track>) -> Unit)? = null

  private var path: String = "/"
    set(value) {
      val previous = field
      field = value
      if (previous != value) {
        onPathChanged?.invoke(value)
      }
    }

  private var content: ResolvedTrack? = null
    set(value) {
      val previous = field
      field = value
      if (previous != value) {
        onContentChanged?.invoke(value)
        Timber.d("content changed", content?.title)
      }
    }

  private var tabs: Array<Track>? = null
    set(value) {
      val previous = field
      field = value
      // Arrays need contentEquals for comparison
      if (value != null && !value.contentEquals(previous)) {
        onTabsChanged?.invoke(value)
      } else if (value == null && previous != null) {
        onTabsChanged?.invoke(emptyArray())
      }
    }

  // LRU cache for individual tracks - keyed by both url and src for O(1) lookup
  private val trackCache = LruCache<String, Track>(3000)

  // LRU cache for resolved content - keyed by path
  // Keeps recently visited paths cached for fast back navigation and tab switching
  // Invalidated via invalidateContentCache() when content changes
  private val contentCache = LruCache<String, ResolvedTrack>(20)

  // Cache for search results - keyed by query string
  private var lastSearchQuery: String? = null
  private var lastSearchResults: Array<Track>? = null

  // Set of favorited track identifiers (src)
  private var favoriteIds = setOf<String>()

  // Navigation tracking to prevent race conditions
  @Volatile
  private var currentNavigationId = 0

  /**
   * Browser configuration containing routes, search, tabs, and request settings. This can be
   * updated dynamically when the configuration changes.
   */
  var config: BrowserConfig = BrowserConfig()

  /**
   * Callback to transform artwork URLs for tracks. Takes a track and optional per-route artwork
   * config, returns ImageSource or null. Injected by AudioBrowser when artwork config is set.
   */
  var artworkUrlResolver: (suspend (Track, MediaRequestConfig?) -> ImageSource?)? = null

  /**
   * Sets the favorited track identifiers. Tracks will have their favorited field hydrated based on
   * this list during browsing.
   */
  fun setFavorites(favorites: List<String>) {
    favoriteIds = favorites.toSet()
    Timber.d("Set ${favoriteIds.size} favorite IDs")
  }

  /**
   * Updates the favorite state for a single track identifier. Called when the heart button is
   * tapped in media controllers.
   */
  fun updateFavorite(id: String, favorited: Boolean) {
    favoriteIds =
      if (favorited) {
        favoriteIds + id
      } else {
        favoriteIds - id
      }
    Timber.d("Updated favorite for '$id' to $favorited (total: ${favoriteIds.size})")
  }

  /**
   * Hydrates the favorited field on a track based on the favoriteIds set. Only hydrates if
   * track.favorited is null (doesn't overwrite API-provided values). Only tracks with src can be
   * favorited.
   */
  private fun hydrateFavorite(track: Track): Track {
    // Don't overwrite API-provided favorites
    if (track.favorited != null) return track
    if (favoriteIds.isEmpty()) return track

    val isFavorited = track.src?.let { favoriteIds.contains(it) } ?: false
    if (!isFavorited) return track

    return Track(
      url = track.url,
      src = track.src,
      artwork = track.artwork,
      artworkSource = track.artworkSource,
      title = track.title,
      subtitle = track.subtitle,
      artist = track.artist,
      album = track.album,
      description = track.description,
      genre = track.genre,
      duration = track.duration,
      style = track.style,
      childrenStyle = track.childrenStyle,
      favorited = true,
      groupTitle = track.groupTitle,
    )
  }

  /** Hydrates favorites on all children of a ResolvedTrack. */
  private fun hydrateChildren(resolvedTrack: ResolvedTrack): ResolvedTrack {
    val children = resolvedTrack.children ?: return resolvedTrack
    val hydratedChildren = children.map { hydrateFavorite(it) }.toTypedArray()
    return resolvedTrack.copy(children = hydratedChildren)
  }

  /** Cache a track by both url and src for O(1) lookup from either key. */
  private fun cacheTrack(track: Track) {
    track.url?.let { trackCache.put(it, track) }
    track.src?.let { trackCache.put(it, track) }
  }

  private fun cacheChildren(resolvedTrack: ResolvedTrack) {
    resolvedTrack.children?.forEach { track -> cacheTrack(track) }
  }

  /**
   * Get a cached Track by mediaId (url or src), or null if not cached. Used by Media3 to rehydrate
   * MediaItem shells with full track metadata. Re-hydrates favorites in case setFavoriteStates was
   * called after caching.
   */
  fun getCachedTrack(mediaId: String): Track? {
    // Try direct lookup first (matches url or src)
    trackCache.get(mediaId)?.let { track ->
      val hydratedTrack = hydrateFavorite(track)
      Timber.d("Cache HIT for mediaId='$mediaId' → '${track.title}'")
      return hydratedTrack
    }

    // Try extracting src from contextual URL
    val trackId = BrowserPathHelper.extractTrackId(mediaId)
    if (trackId != null) {
      trackCache.get(trackId)?.let { track ->
        val hydratedTrack = hydrateFavorite(track)
        Timber.d("Cache HIT (extracted src) for mediaId='$mediaId' → '${track.title}'")
        return hydratedTrack
      }
    }

    Timber.w("Cache MISS for mediaId='$mediaId'")
    return null
  }

  /**
   * Resolves multiple media IDs from cache. Throws IllegalStateException if any mediaId is not
   * found in cache.
   *
   * @param mediaIds The media IDs to resolve
   * @return List of Track objects
   */
  fun resolveMediaIdsFromCache(mediaIds: List<String>): List<Track> {
    return mediaIds.map { mediaId ->
      Timber.d("=== Resolving from cache: mediaId='$mediaId' ===")

      getCachedTrack(mediaId)?.let { cachedTrack ->
        Timber.d("→ Found cached Track: '${cachedTrack.title}'")
        return@map cachedTrack
      }

      // Cache miss - this indicates a bug in our caching system
      Timber.e("→ Cache MISS for mediaId='$mediaId' - this should not happen")
      throw IllegalStateException(
        "MediaItem not found in cache: $mediaId. This indicates a bug in the caching system."
      )
    }
  }

  /**
   * **Media3/Android Auto Integration Entry Point**
   *
   * Resolves Media3 MediaItems for playback, with special handling for Android Auto queue
   * expansion. Called exclusively from `MediaSessionCallback.onSetMediaItems()`.
   *
   * Behavior:
   * - Single item: Attempts queue expansion (Android Auto album/playlist restoration)
   * - Multiple items OR expansion fails: Falls back to cache resolution
   *
   * @param mediaItems List of Media3 MediaItems requested for playback
   * @param startIndex Index of the item to start playing
   * @param startPositionMs Position within the start item to begin playback
   * @return MediaSession.MediaItemsWithStartPosition ready for Media3
   * @throws IllegalStateException if any mediaId is not found in cache
   */
  suspend fun resolveMediaItemsForPlayback(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): MediaSession.MediaItemsWithStartPosition {
    // Android Auto queue expansion: single track → full album/playlist
    if (mediaItems.size == 1) {
      val mediaItem = mediaItems[0]

      // Handle search query - user initiated playback from search results
      val searchQuery = mediaItem.requestMetadata.searchQuery
      if (searchQuery != null) {
        Timber.d("Handling search playback request for query: $searchQuery")

        // Execute search (will hit cache if already performed)
        val searchResults = search(searchQuery)
        val searchTracks = searchResults.children

        if (searchTracks != null && searchTracks.isNotEmpty()) {
          // Find the selected track in search results
          val mediaId = mediaItem.mediaId
          val selectedIndex =
            searchTracks.indexOfFirst { track -> track.url == mediaId || track.src == mediaId }

          if (selectedIndex >= 0) {
            Timber.d(
              "Playing search result at index $selectedIndex of ${searchTracks.size} results"
            )

            // Convert to Media3 MediaItems
            val searchMediaItems = searchTracks.map { track -> TrackFactory.toMedia3(track) }

            return MediaSession.MediaItemsWithStartPosition(
              searchMediaItems,
              selectedIndex,
              startPositionMs,
            )
          } else {
            Timber.w("Selected track not found in search results, falling back to single track")
          }
        } else {
          Timber.w("Search returned no results for query: $searchQuery")
        }
      }

      val mediaId = mediaItems[0].mediaId

      if (BrowserPathHelper.isContextual(mediaId)) {
        Timber.d("Attempting queue expansion for mediaId='$mediaId'")

        val expanded = expandQueueFromContextualUrl(mediaId)

        if (expanded != null) {
          val (tracks, selectedIndex) = expanded

          // Convert to Media3 MediaItems
          val expandedMediaItems = tracks.map { track -> TrackFactory.toMedia3(track) }

          return MediaSession.MediaItemsWithStartPosition(
            expandedMediaItems,
            selectedIndex,
            startPositionMs,
          )
        }
      }
    }

    // No expansion - resolve from cache
    val mediaIds = mediaItems.map { it.mediaId }
    val cachedTracks = resolveMediaIdsFromCache(mediaIds)

    // Convert to Media3 MediaItems
    val resolvedMediaItems = cachedTracks.map { track -> TrackFactory.toMedia3(track) }

    return MediaSession.MediaItemsWithStartPosition(resolvedMediaItems, startIndex, startPositionMs)
  }

  /**
   * Validates that a track has either url or src for stable identification. Throws
   * IllegalStateException if validation fails.
   */
  private fun validateTrack(track: Track, context: String) {
    if (track.url == null && track.src == null) {
      throw IllegalStateException(
        "$context must have either 'url' or 'src' property for stable identification. Track: ${track.title}"
      )
    }
  }

  suspend fun resolve(path: String, useCache: Boolean = true): ResolvedTrack {
    Timber.d("=== RESOLVE: path='$path' (useCache=$useCache) ===")

    // Strip __trackId from contextual URLs (e.g., "/library/radio?__trackId=song.mp3" →
    // "/library/radio")
    // This allows resolving the parent container for tracks referenced by contextual URL
    val normalizedPath = BrowserPathHelper.stripTrackId(path)
    if (normalizedPath != path) {
      Timber.d("Stripped __trackId from contextual URL: '$normalizedPath'")
    }

    // Check content cache first
    if (useCache) {
      contentCache.get(normalizedPath)?.let { cached ->
        Timber.d("Content cache HIT for path='$normalizedPath'")
        // Re-hydrate favorites in case they changed since caching
        return hydrateChildren(cached)
      }
      Timber.d("Content cache MISS for path='$normalizedPath'")
    }

    val resolvedTrack = resolveUncached(normalizedPath)

    // Cache the resolved content for future navigation
    contentCache.put(normalizedPath, resolvedTrack)

    // Cache children for Media3 track lookups (getCachedTrack)
    cacheChildren(resolvedTrack)

    return hydrateChildren(resolvedTrack)
  }

  /**
   * Invalidates the content cache for a specific path.
   * Called when content at that path has changed (e.g., via notifyContentChanged).
   *
   * @param path The container path to invalidate (e.g., "/library/radio")
   * @throws IllegalArgumentException if passed a contextual URL (contains __trackId)
   */
  fun invalidateContentCache(path: String) {
    require(!BrowserPathHelper.isContextual(path)) {
      "invalidateContentCache() expects a container path, not a contextual URL: $path"
    }
    contentCache.remove(path)
    Timber.d("Invalidated content cache for path='$path'")
  }

  private suspend fun resolveUncached(path: String): ResolvedTrack {
    val routes = config.routes
    if (routes.isNullOrEmpty()) {
      Timber.e("No routes configured for path: $path")
      throw ContentNotFoundException(path)
    }

    // Find best matching route
    val (routeEntry, routeParams) =
      findBestRouteMatch(path, routes)
        ?: run {
          Timber.e("No route matched for path: $path")
          throw ContentNotFoundException(path)
        }

    Timber.d("Matched route: ${routeEntry.path} with params: $routeParams")

    // Resolve the track from the route
    val resolvedTrack = resolveRouteEntry(routeEntry, path, routeParams)

    // Get effective artwork config: per-route overrides global
    val effectiveArtworkConfig = routeEntry.artwork ?: config.artwork

    // Transform children: generate contextual URLs and transform artwork URLs
    val transformedChildren =
      resolvedTrack.children?.let { children ->
        coroutineScope {
          children
            .mapIndexed { index, track ->
              async {
                // Validate that track has stable identifier
                validateTrack(track, "Child track")

                var transformedTrack = track

                // Generate contextual URLs for playable tracks
                // Always regenerate to reflect the current browsing context, not the original context
                // (e.g., a track favorited from an album should use /favorites context when browsed there)
                if (track.src != null) {
                  val contextualUrl = BrowserPathHelper.build(path, track.src)
                  transformedTrack = transformedTrack.copy(url = contextualUrl)

                  Timber.d(
                    "[$path] Child[$index] '${track.title}': Playable, contextualUrl=$contextualUrl (src=${track.src})"
                  )
                } else {
                  Timber.d(
                    "[$path] Child[$index] '${track.title}': Browsable with url=${track.url}"
                  )
                }

                // Transform artwork URL if resolver is configured
                val resolver = artworkUrlResolver
                if (resolver != null) {
                  transformedTrack =
                    transformArtworkUrl(
                      transformedTrack,
                      effectiveArtworkConfig,
                      resolver,
                      path,
                      index,
                    )
                }

                transformedTrack
              }
            }
            .awaitAll()
        }
      }

    // Return resolved track with transformed children
    return if (transformedChildren != null) {
      resolvedTrack.copy(children = transformedChildren.toTypedArray())
    } else {
      resolvedTrack
    }
  }

  /**
   * Transforms a track's artwork using the configured resolver. Populates artworkSource with the
   * transformed ImageSource, keeping artwork unchanged. Handles all edge cases: undefined returns,
   * errors, missing artwork.
   */
  private suspend fun transformArtworkUrl(
    track: Track,
    artworkConfig: MediaRequestConfig?,
    resolver: suspend (Track, MediaRequestConfig?) -> ImageSource?,
    path: String,
    index: Int,
  ): Track {
    // No artwork config and no track.artwork - nothing to transform
    if (artworkConfig == null && track.artwork == null) {
      return track
    }

    return try {
      val imageSource = resolver(track, artworkConfig)

      when {
        // resolve returned null → no artwork source
        imageSource == null -> {
          Timber.d(
            "[$path] Child[$index] '${track.title}': Artwork resolver returned null, no artworkSource"
          )
          track.copy(artworkSource = null)
        }
        // resolve returned ImageSource → set artworkSource
        else -> {
          Timber.d("[$path] Child[$index] '${track.title}': artworkSource set: ${imageSource.uri}")
          track.copy(artworkSource = imageSource)
        }
      }
    } catch (e: Exception) {
      // resolve threw → log error, clear artworkSource to avoid broken images
      Timber.e(
        e,
        "[$path] Child[$index] '${track.title}': Artwork transform failed, clearing artworkSource",
      )
      track.copy(artworkSource = null)
    }
  }

  /**
   * Expands a contextual URL into a queue of playable tracks.
   *
   * Used when navigating to a track to load it with its full album/playlist context. Returns only
   * the selected track if singleTrack is true.
   *
   * @param contextualUrl The contextual URL (e.g., "/album?__trackId=song.mp3")
   * @return Pair of (tracks array, selected track index), or null if expansion fails
   */
  suspend fun expandQueueFromContextualUrl(contextualUrl: String): Pair<Array<Track>, Int>? {
    val trackId = BrowserPathHelper.extractTrackId(contextualUrl) ?: return null

    Timber.d("Expanding queue from contextual URL: $contextualUrl (trackId=$trackId)")

    try {
      // Resolve the parent container to get all siblings
      val parentPath = BrowserPathHelper.stripTrackId(contextualUrl)
      val parentResolvedTrack = resolve(parentPath)
      val children = parentResolvedTrack.children

      if (children.isNullOrEmpty()) {
        Timber.w("Parent has no children, cannot expand queue")
        return null
      }

      // Filter to only playable tracks (tracks with src)
      val playableTracks = children.filter { track -> track.src != null }

      if (playableTracks.isEmpty()) {
        Timber.w("Parent has no playable tracks, cannot expand queue")
        return null
      }

      // Find the index of the selected track in the playable tracks array
      val selectedIndex = playableTracks.indexOfFirst { track -> track.src == trackId }

      if (selectedIndex < 0) {
        Timber.w("Track with src='$trackId' not found in playable children")
        return null
      }

      // Check singleTrack setting - if true, return only the selected track
      if (config.singleTrack) {
        Timber.d("singleTrack=true - returning single track at index $selectedIndex")
        return Pair(arrayOf(playableTracks[selectedIndex]), 0)
      }

      Timber.d(
        "singleTrack=false - returning ${playableTracks.size} playable tracks (from ${children.size} total), starting at index $selectedIndex"
      )
      return Pair(playableTracks.toTypedArray(), selectedIndex)
    } catch (e: Exception) {
      Timber.e(e, "Error expanding queue from contextual URL: $contextualUrl")
      return null
    }
  }

  /**
   * Navigate to a path and return browser content.
   *
   * Uses a navigation ID to prevent race conditions when multiple navigations
   * overlap. Only the most recent navigation's result is applied.
   *
   * @param path The path to navigate to (e.g., "/artists/123")
   * @return ResolvedTrack containing the navigation result
   */
  suspend fun navigate(path: String): ResolvedTrack {
    Timber.d("Navigating to path: $path")

    // Increment navigation ID and capture for this navigation
    val navigationId = ++currentNavigationId

    this.path = path
    this.content = null // Clear content immediately to show loading state
    val content = resolve(path)

    // Only apply result if this is still the current navigation
    if (navigationId == currentNavigationId) {
      this.content = content
    }
    return content
  }

  /**
   * Refresh the current path's content without changing navigation state.
   * Used for background refreshes (e.g., when content changes via notifyContentChanged).
   * Bypasses content cache to fetch fresh data. Errors are silently ignored.
   *
   * Uses navigation ID tracking to prevent race conditions.
   */
  suspend fun refresh() {
    // Increment navigation ID and capture for this refresh
    val navigationId = ++currentNavigationId

    val currentPath = path
    Timber.d("Refreshing content for path: $currentPath")

    try {
      contentCache.remove(currentPath)
      val content = resolve(currentPath, useCache = false)

      // Only apply result if this is still the current navigation
      if (navigationId == currentNavigationId) {
        this.content = content
      }
    } catch (e: Exception) {
      Timber.e(e, "Error refreshing content for path: $currentPath")
    }
  }

  /**
   * Get cached search results for a query. Used by Media3 onGetSearchResult() callback to retrieve
   * previously executed search.
   *
   * @param query The search query string
   * @return Array of Track results, or null if not found
   */
  fun getCachedSearchResults(query: String): Array<Track>? {
    if (query != lastSearchQuery) return null
    return lastSearchResults?.map { hydrateFavorite(it) }?.toTypedArray()
  }

  /**
   * Search for tracks and return playable results.
   *
   * @param query The search query string
   * @return Array of playable tracks, or null if no results or search not configured
   */
  suspend fun searchPlayable(query: String): Array<Track>? {
    return searchPlayable(
      SearchParams(
        query = query,
        mode = null,
        genre = null,
        artist = null,
        album = null,
        title = null,
        playlist = null,
      )
    )
  }

  /**
   * Search for tracks and return playable results. If the first result is browsable, resolves it
   * and returns its children. If the first result is playable, returns it. Used for voice search
   * "play X" commands.
   *
   * @param params The structured search parameters
   * @return Array of playable tracks, or null if no results or search not configured
   */
  suspend fun searchPlayable(params: SearchParams): Array<Track>? {
    val searchResults = search(params)
    val tracks = searchResults.children

    if (tracks.isNullOrEmpty()) {
      return null
    }

    val firstResult = tracks[0]

    // Check if result is browsable-only (container/route) vs playable
    // If it's browsable but also playable (has src or playable=true), treat it as playable
    val tracksToFilter =
      if (firstResult.src == null) {
        Timber.d("First search result is browsable-only, resolving: ${firstResult.url}")
        val resolvedTrack = resolve(firstResult.url!!)
        resolvedTrack.children
          ?.filter { it.src != null }
          ?.takeIf { it.isNotEmpty() }
          ?.toTypedArray() ?: tracks
      } else {
        tracks
      }

    return tracksToFilter.filter { it.src != null }.takeIf { it.isNotEmpty() }?.toTypedArray()
  }

  /**
   * Search for tracks using the configured search source.
   *
   * @param query The search query string
   * @return ResolvedTrack containing search results as children
   */
  suspend fun search(query: String): ResolvedTrack {
    return search(
      SearchParams(
        query = query,
        mode = null,
        genre = null,
        artist = null,
        album = null,
        title = null,
        playlist = null,
      )
    )
  }

  /**
   * Search for tracks using the configured search source. Returns a ResolvedTrack at the path
   * /__search?q=query with children containing results. Always executes a fresh search and caches
   * results for onGetSearchResult() retrieval.
   *
   * @param params The structured search parameters
   * @return ResolvedTrack containing search results as children
   */
  suspend fun search(params: SearchParams): ResolvedTrack {
    Timber.d("Executing fresh search for: ${params.query} (mode=${params.mode})")

    val searchPath = BrowserPathHelper.createSearchPath(params.query)

    try {
      // Execute search
      val searchResults = resolveSearch(params)

      // Create ResolvedTrack
      val searchResolvedTrack =
        ResolvedTrack(
          url = searchPath,
          title = "Search: ${params.query}",
          children = searchResults,
          artwork = null,
          artworkSource = null,
          artist = null,
          description = null,
          subtitle = null,
          album = null,
          genre = null,
          duration = null,
          src = null,
          style = null,
          childrenStyle = null,
          favorited = null,
          groupTitle = null,
        )

      // Cache search results for getCachedSearchResults()
      lastSearchQuery = params.query
      lastSearchResults = searchResults

      // Cache individual tracks for Media3 lookups
      cacheChildren(searchResolvedTrack)
      Timber.d(
        "Cached search results for query: ${params.query} with ${searchResults.size} results"
      )

      return searchResolvedTrack
    } catch (e: Exception) {
      Timber.e(e, "Error during search for query: ${params.query}")

      // Return empty search result on error
      val emptySearchResult =
        ResolvedTrack(
          url = searchPath,
          title = "Search: ${params.query}",
          children = emptyArray(),
          artwork = null,
          artworkSource = null,
          artist = null,
          description = null,
          subtitle = null,
          album = null,
          genre = null,
          duration = null,
          src = null,
          style = null,
          childrenStyle = null,
          favorited = null,
          groupTitle = null,
        )

      return emptySearchResult
    }
  }

  /**
   * Get the current navigation path.
   *
   * @return Current path string
   */
  fun getPath(): String {
    return path
  }

  /**
   * Get the current loaded content.
   *
   * @return Current ResolvedTrack content or null if none loaded
   */
  fun getContent(): ResolvedTrack? {
    return content
  }

  /**
   * Get the current cached tabs.
   *
   * @return Current tabs array or null if none loaded
   */
  fun getTabs(): Array<Track>? {
    return tabs
  }

  /** Set callback for path changes. */
  fun setOnPathChanged(callback: (String) -> Unit) {
    onPathChanged = callback
  }

  /** Set callback for content changes. */
  fun setOnContentChanged(callback: (ResolvedTrack?) -> Unit) {
    onContentChanged = callback
  }

  /** Set callback for tabs changes. */
  fun setOnTabsChanged(callback: (Array<Track>) -> Unit) {
    onTabsChanged = callback
  }

  /**
   * Query navigation tabs from the configured tabs source. This is an async operation that resolves
   * the tabs configuration.
   *
   * @return Array of Track objects representing tabs
   */
  suspend fun queryTabs(): Array<Track> {
    // Return cached tabs if available
    this.tabs?.let {
      return it
    }

    Timber.d("Getting navigation tabs")

    val tabs = resolveTabs()

    // Validate tabs have stable identifiers
    tabs.forEachIndexed { index, tab ->
      validateTrack(tab, "Tab")
      Timber.d("[TABS] Tab[$index] '${tab.title}': url=${tab.url}")
    }

    this.tabs = tabs
    return tabs
  }

  companion object {
    /** Internal path used for the default/root browse source */
    internal const val DEFAULT_ROUTE_PATH = "__default__"

    /** Internal path used for navigation tabs */
    internal const val TABS_ROUTE_PATH = "__tabs__"

    /** Internal path used for search */
    internal const val SEARCH_ROUTE_PATH = "__search__"
  }

  /**
   * Find the best matching route entry for a path. Uses SimpleRouter for pattern matching, with
   * __default__ as lowest priority fallback.
   */
  private fun findBestRouteMatch(
    path: String,
    routes: Array<NativeRouteEntry>,
  ): Pair<NativeRouteEntry, Map<String, String>>? {
    // Convert to map for router compatibility, excluding default fallback
    val routeMap = routes.filter { it.path != DEFAULT_ROUTE_PATH }.associateBy { it.path }

    // Try to find a specific route match
    router.findBestMatch(path, routeMap)?.let { (routePattern, match) ->
      val routeEntry = routeMap[routePattern]!!
      return Pair(routeEntry, match.params)
    }

    // Fall back to default route if present
    routes
      .find { it.path == DEFAULT_ROUTE_PATH }
      ?.let { defaultRoute ->
        return Pair(defaultRoute, emptyMap())
      }

    return null
  }

  /**
   * Resolve a NativeRouteEntry into a ResolvedTrack. The entry has flattened browse options:
   * callback, config, or static.
   */
  private suspend fun resolveRouteEntry(
    entry: NativeRouteEntry,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    // Priority: callback > config > static
    entry.browseCallback?.let { callback ->
      Timber.d("Resolving route via callback")
      val param = BrowserSourceCallbackParam(path, routeParams)
      val promise = callback.invoke(param)
      val innerPromise = promise.await()
      return innerPromise.await()
    }

    entry.browseConfig?.let { apiConfig ->
      Timber.d("Resolving route via API config")
      return executeApiRequest(apiConfig, path, routeParams)
    }

    entry.browseStatic?.let { staticTrack ->
      Timber.d("Resolving route via static track")
      return staticTrack
    }

    throw ContentNotFoundException(path)
  }

  /**
   * Resolve search via the __search__ route entry. The entry has searchCallback or searchConfig.
   */
  private suspend fun resolveSearch(params: SearchParams): Array<Track> {
    val routes = config.routes ?: return emptyArray()

    // Find the __search__ route entry
    val searchEntry = routes.find { it.path == SEARCH_ROUTE_PATH }
    if (searchEntry == null) {
      Timber.w("No search route configured")
      return emptyArray()
    }

    searchEntry.searchCallback?.let { callback ->
      Timber.d("Resolving search via callback")
      val promise = callback.invoke(params)
      val innerPromise = promise.await()
      return innerPromise.await()
    }

    searchEntry.searchConfig?.let { apiConfig ->
      Timber.d("Resolving search via API config")
      return executeSearchApiRequest(apiConfig, params)
    }

    Timber.w("Search route has no callback or config")
    return emptyArray()
  }

  /**
   * Resolve tabs via the __tabs__ route entry. Returns children of the resolved track, or empty
   * array if no tabs configured.
   */
  private suspend fun resolveTabs(): Array<Track> {
    val routes = config.routes ?: return emptyArray()

    // Find the __tabs__ route entry
    val tabsEntry = routes.find { it.path == TABS_ROUTE_PATH }
    if (tabsEntry == null) {
      Timber.d("No tabs route configured")
      return emptyArray()
    }

    Timber.d("Resolving tabs via route entry")
    val resolvedTrack = resolveRouteEntry(tabsEntry, TABS_ROUTE_PATH, emptyMap())

    return resolvedTrack.children ?: emptyArray()
  }

  /**
   * Execute an API request for browser content. Handles URL parameter substitution, config merging,
   * and transforms.
   */
  private suspend fun executeApiRequest(
    apiConfig: TransformableRequestConfig,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    return withContext(Dispatchers.IO) {
      // 1. Start with base config, using the navigation path as default
      val baseConfig =
        config.request?.let { req ->
          RequestConfigBuilder.toRequestConfig(req).copy(path = req.path ?: path)
        }
          ?: RequestConfig(
            path = path,
            method = null,
            baseUrl = null,
            headers = null,
            query = null,
            body = null,
            contentType = null,
            userAgent = null,
          )
      val mergedConfig = RequestConfigBuilder.mergeConfig(baseConfig, apiConfig, routeParams)

      // 2. Build and execute HTTP request
      val httpRequest = RequestConfigBuilder.buildHttpRequest(mergedConfig)
      val response = httpClient.request(httpRequest)

      response.fold(
        onSuccess = { httpResponse ->
          if (httpResponse.isSuccessful) {
            // 3. Parse response as ResolvedTrack
            val jsonResolvedTrack = json.decodeFromString<JsonResolvedTrack>(httpResponse.body)
            jsonResolvedTrack.toNitro()
          } else {
            Timber.w(
              "HTTP request failed with status ${httpResponse.code} for ${httpRequest.url}: ${httpResponse.body}"
            )
            throw HttpStatusException(httpResponse.code, "Server returned ${httpResponse.code}")
          }
        },
        onFailure = { exception ->
          Timber.e(exception, "HTTP request failed")
          throw NetworkException("Network request failed: ${exception.message}", exception)
        },
      )
    }
  }

  /**
   * Execute an API request for search results. Automatically adds search parameters to request
   * query:
   * - q: The search query string (always included)
   * - mode: The search mode (any, genre, artist, album, song, playlist) - omitted for unstructured
   *   search
   * - genre, artist, album, title, playlist: Included only when non-null
   *
   * Transform callbacks can access and modify all parameters as needed.
   */
  private suspend fun executeSearchApiRequest(
    apiConfig: TransformableRequestConfig,
    params: SearchParams,
  ): Array<Track> {
    return withContext(Dispatchers.IO) {
      try {
        // 1. Start with base config
        val baseConfig =
          config.request?.let { RequestConfigBuilder.toRequestConfig(it) }
            ?: RequestConfig(
              method = null,
              path = null,
              baseUrl = null,
              headers = null,
              query = null,
              body = null,
              contentType = null,
              userAgent = null,
            )

        // 2. Build query parameters from SearchParams
        val searchQueryParams = buildMap {
          put("q", params.query)
          params.mode?.let { put("mode", it.toString().lowercase()) }
          params.genre?.let { put("genre", it) }
          params.artist?.let { put("artist", it) }
          params.album?.let { put("album", it) }
          params.title?.let { put("title", it) }
          params.playlist?.let { put("playlist", it) }
        }

        // 3. Create a copy of API config with added search parameters
        val searchConfig =
          TransformableRequestConfig(
            transform = apiConfig.transform,
            method = apiConfig.method,
            path = apiConfig.path,
            baseUrl = apiConfig.baseUrl,
            headers = apiConfig.headers,
            query = (apiConfig.query ?: emptyMap()) + searchQueryParams,
            body = apiConfig.body,
            contentType = apiConfig.contentType,
            userAgent = apiConfig.userAgent,
          )

        // 3. Merge configs and apply transform if provided
        var mergedConfig = RequestConfigBuilder.mergeConfig(baseConfig, searchConfig, emptyMap())

        // 4. Build and execute HTTP request
        val httpRequest = RequestConfigBuilder.buildHttpRequest(mergedConfig)
        val response = httpClient.request(httpRequest)

        response.fold(
          onSuccess = { httpResponse ->
            if (httpResponse.isSuccessful) {
              // 4. Parse response as Track array
              val jsonTracks = json.decodeFromString<List<JsonTrack>>(httpResponse.body)
              jsonTracks.map { it.toNitro() }.toTypedArray()
            } else {
              Timber.w(
                "Search HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}"
              )
              emptyArray()
            }
          },
          onFailure = { exception ->
            Timber.e(exception, "Search HTTP request failed")
            emptyArray()
          },
        )
      } catch (e: Exception) {
        Timber.e(e, "Error executing search API request")
        emptyArray()
      }
    }
  }
}

/** Exception thrown when no content is configured for a requested path. */
class ContentNotFoundException(val path: String) :
  Exception("No content configured for path: $path")

/** Exception thrown when an HTTP request fails with a non-2xx status code. */
class HttpStatusException(val statusCode: Int, message: String) : Exception(message)

/** Exception thrown when a network request fails (connection error, timeout, etc). */
class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Configuration object that holds all browser settings. Uses flattened structure matching
 * NativeBrowserConfiguration from JS.
 */
data class BrowserConfig(
  val request: TransformableRequestConfig? = null,
  val media: MediaRequestConfig? = null,
  val artwork: MediaRequestConfig? = null,
  // Routes as array with flattened entries (includes __tabs__, __search__, and __default__ special
  // routes)
  val routes: Array<NativeRouteEntry>? = null,
  // Behavior
  val singleTrack: Boolean = false,
  val androidControllerOfflineError: Boolean = true,
) {
  /** Returns true if search functionality is configured (either callback or config). */
  val hasSearch: Boolean
    get() {
      val searchEntry = routes?.find { it.path == BrowserManager.SEARCH_ROUTE_PATH }
      return searchEntry?.searchCallback != null || searchEntry?.searchConfig != null
    }
}
