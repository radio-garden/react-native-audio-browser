package com.audiobrowser.browser

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.audiobrowser.SearchSource
import com.audiobrowser.TabsSource
import com.audiobrowser.http.HttpClient
import com.audiobrowser.http.RequestConfigBuilder
import com.audiobrowser.util.ContextualUrlHelper
import com.audiobrowser.util.ResolvedTrackFactory
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.BrowserSource
import com.margelo.nitro.audiobrowser.BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.Dispatchers
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

  // Cache resolved tracks by mediaId/path to avoid redundant HTTP requests
  private val resolvedTrackCache = mutableMapOf<String, ResolvedTrack>()

  // Cache individual tracks (children) for Media3 MediaItem lookup
  private val trackCache = mutableMapOf<String, Track>()

  /**
   * Browser configuration containing routes, search, tabs, and request settings. This can be
   * updated dynamically when the configuration changes.
   */
  var config: BrowserConfig = BrowserConfig()

  /** Get a cached ResolvedTrack by mediaId/path, or null if not cached. */
  fun getCachedResolvedTrack(mediaId: String): ResolvedTrack? {
    return resolvedTrackCache[mediaId]
  }

  /**
   * Get a cached Track by mediaId (url or src), or null if not cached. Used by Media3 to rehydrate
   * MediaItem shells with full track metadata.
   */
  fun getCachedTrack(mediaId: String): Track? {
    val track = trackCache[mediaId]
    if (track != null) {
      Timber.d("Cache HIT for mediaId='$mediaId' → track='${track.title}'")
    } else {
      Timber.w("Cache MISS for mediaId='$mediaId'")
    }
    return track
  }

  /**
   * Resolves multiple media IDs from cache.
   * Checks both ResolvedTrack cache (for containers) and Track cache (for individual items).
   * Throws IllegalStateException if any mediaId is not found in cache.
   *
   * @param mediaIds The media IDs to resolve
   * @return List of resolved items (can be mixed ResolvedTrack and Track)
   */
  fun resolveMediaIdsFromCache(mediaIds: List<String>): List<Any> {
    return mediaIds.map { mediaId ->
      Timber.d("=== Resolving from cache: mediaId='$mediaId' ===")

      // First check if this was a navigated ResolvedTrack
      getCachedResolvedTrack(mediaId)?.let { cachedResolvedTrack ->
        Timber.d("→ Found cached ResolvedTrack: '${cachedResolvedTrack.title}'")
        return@map cachedResolvedTrack
      }

      // Then check if this is a cached child Track
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
   * Resolves Media3 MediaItems for playback, with special handling for Android Auto queue expansion.
   * Called exclusively from `MediaSessionCallback.onSetMediaItems()`.
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
    startPositionMs: Long
  ): MediaSession.MediaItemsWithStartPosition {
    // Android Auto queue expansion: single track → full album/playlist
    if (mediaItems.size == 1) {
      val mediaItem = mediaItems[0]
      // TODO: handle -> mediaItems[0].requestMetadata.searchQuery
      if (mediaItem.requestMetadata.searchQuery != null) {
        // This is a search request
        Timber.w("Search request: ${mediaItem.requestMetadata.searchQuery}")
      }
      val mediaId = mediaItems[0].mediaId

      if (ContextualUrlHelper.isContextual(mediaId)) {
        Timber.d("Attempting queue expansion for mediaId='$mediaId'")

        val expanded = expandQueueFromContextualUrl(mediaId)

        if (expanded != null) {
          val (tracks, selectedIndex) = expanded

          // Convert to Media3 MediaItems
          val expandedMediaItems = tracks.map { track -> TrackFactory.toMedia3(track) }

          return MediaSession.MediaItemsWithStartPosition(
            expandedMediaItems,
            selectedIndex,
            startPositionMs
          )
        }
      }
    }

    // No expansion - resolve from cache
    val mediaIds = mediaItems.map { it.mediaId }
    val cachedItems = resolveMediaIdsFromCache(mediaIds)

    // Convert to Media3 MediaItems
    val resolvedMediaItems = cachedItems.map { item ->
      when (item) {
        is ResolvedTrack -> ResolvedTrackFactory.toMedia3(item)
        is Track -> TrackFactory.toMedia3(item)
        else -> throw IllegalStateException("Unexpected item type: ${item::class.simpleName}")
      }
    }

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

  suspend fun resolve(path: String): ResolvedTrack {
    Timber.d("=== RESOLVE: path='$path' ===")

    // Strip __trackId from contextual URLs (e.g., "/library/radio?__trackId=song.mp3" → "/library/radio")
    // This allows resolving the parent container for tracks referenced by contextual URL
    val normalizedPath = ContextualUrlHelper.stripTrackId(path)
    if (normalizedPath != path) {
      Timber.d("Stripped __trackId from contextual URL: '$normalizedPath'")
    }

    // Check cache with normalized path
    resolvedTrackCache[normalizedPath]?.let {
      Timber.d(
        "Returning cached ResolvedTrack: url='${it.url}', title='${it.title}', children=${it.children?.size ?: 0}"
      )
      return it
    }

    Timber.d("Cache miss, resolving path: '$normalizedPath'")
    val resolvedTrack = resolveUncached(normalizedPath)

    // Cache the resolved track
    resolvedTrackCache[normalizedPath] = resolvedTrack
    Timber.d(
      "Cached ResolvedTrack: url='${resolvedTrack.url}', title='${resolvedTrack.title}', children=${resolvedTrack.children?.size ?: 0}"
    )

    return resolvedTrack
  }

  private suspend fun resolveUncached(path: String): ResolvedTrack {
    // Resolve the track from routes or browse fallback
    val resolvedTrack =
      config.routes
        ?.takeUnless { it.isEmpty() }
        ?.let { routes ->
          router.findBestMatch(path, routes)?.let { (routePattern, match) ->
            val browserSource = routes[routePattern]!!
            val routeParams = match.params

            Timber.d("Matched route: $routePattern with params: $routeParams")
            resolveBrowserSource(browserSource, path, routeParams)
          }
        }
        ?: config.browse?.let { browseSource ->
          Timber.d("No route matched, using browse fallback")
          resolveBrowseSource(browseSource, path, emptyMap())
        }
        ?: run {
          Timber.e("No route matched and no browse fallback configured for path: $path")
          throw ContentNotFoundException(path)
        }

    // Transform and cache children
    val transformedChildren =
      resolvedTrack.children?.mapIndexed { index, track ->
        // Validate that track has stable identifier
        validateTrack(track, "Child track")

        // Only generate contextual URLs for playable-only tracks (no url, just src)
        // Browsable tracks keep their original url
        if (track.url != null) {
          // Track is browsable - keep original url and cache by it
          Timber.d("[$path] Child[$index] '${track.title}': Browsable with url=${track.url}")
          trackCache[track.url] = track
          track
        } else {
          // Track is playable-only - generate contextual URL for Media3
          // Safe to use !! because validateTrack ensures (url != null || src != null)
          val contextualUrl = ContextualUrlHelper.build(path, track.src!!)
          val trackWithContextualUrl = track.copy(url = contextualUrl)

          Timber.d(
            "[$path] Child[$index] '${track.title}': Playable-only, generated contextualUrl=$contextualUrl (src=${track.src})"
          )
          trackCache[contextualUrl] = trackWithContextualUrl

          trackWithContextualUrl
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
   * Expands a contextual URL into a queue of playable tracks.
   *
   * Used when navigating to a track to load it with its full album/playlist context.
   *
   * @param contextualUrl The contextual URL (e.g., "/album?__trackId=song.mp3")
   * @return Pair of (tracks array, selected track index), or null if expansion fails
   */
  suspend fun expandQueueFromContextualUrl(contextualUrl: String): Pair<Array<Track>, Int>? {
    val trackId = ContextualUrlHelper.extractTrackId(contextualUrl) ?: return null

    Timber.d("Expanding queue from contextual URL: $contextualUrl (trackId=$trackId)")

    try {
      // Resolve the parent container to get all siblings
      val parentPath = ContextualUrlHelper.stripTrackId(contextualUrl)
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

      Timber.d(
        "Queue expanded: ${playableTracks.size} playable tracks (from ${children.size} total), starting at index $selectedIndex"
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
   * @param path The path to navigate to (e.g., "/artists/123")
   * @return ResolvedTrack containing the navigation result
   */
  suspend fun navigate(path: String): ResolvedTrack {
    Timber.d("Navigating to path: $path")

    this.path = path
    val content = resolve(path)
    this.content = content
    return content
  }

  /**
   * Search for tracks using the configured search source.
   *
   * @param query The search query string
   * @return Array of Track results
   */
  suspend fun search(query: String): Array<Track> {
    Timber.d("Searching for: $query")

    try {
      return config.search?.let { searchSource -> resolveSearchSource(searchSource, query) }
        ?: run {
          Timber.w("Search requested but no search source configured")
          emptyArray()
        }
    } catch (e: Exception) {
      Timber.e(e, "Error during search for query: $query")
      return emptyArray()
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

    val tabs =
      (config.tabs?.let { tabsSource -> resolveTabsSource(tabsSource) }
        ?: run {
          Timber.d("No tabs configured")
          emptyArray()
        })

    // Validate and cache tabs for Media3 MediaItem lookup
    tabs.forEachIndexed { index, tab ->
      validateTrack(tab, "Tab")

      val tabId = tab.url ?: tab.src!!

      Timber.d("[TABS] Tab[$index] '${tab.title}': Cached with id=$tabId")
      trackCache[tabId] = tab
    }

    this.tabs = tabs
    return tabs
  }

  /**
   * Resolve a BrowserSource into a BrowserList. Handles the three possible types: static
   * BrowserList, callback, or API config.
   */
  private suspend fun resolveBrowserSource(
    source: BrowserSource,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    return source.match(
      // Callback function
      first = { callback ->
        Timber.d("Resolving browser source via callback")
        val param = BrowserSourceCallbackParam(path, routeParams)
        val promise = callback.invoke(param)
        val innerPromise = promise.await()
        innerPromise.await()
      },
      // Static BrowserList
      second = { staticList ->
        Timber.d("Resolving browser source via static list")
        staticList
      },
      // API configuration
      third = { apiConfig ->
        Timber.d("Resolving browser source via API config")
        executeApiRequest(apiConfig, routeParams)
      },
    )
  }

  /** Resolve a BrowseSource (which doesn't include static BrowserList option). */
  private suspend fun resolveBrowseSource(
    source: BrowseSource,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    return source.match(
      // Callback function
      first = { callback ->
        Timber.d("Resolving browse source via callback")
        val param = BrowserSourceCallbackParam(path, routeParams)
        val promise = callback.invoke(param)
        val innerPromise = promise.await()
        innerPromise.await()
      },
      // Static BrowserList
      second = { staticList ->
        Timber.d("Resolving browse source via static list")
        staticList
      },
      // API configuration
      third = { apiConfig ->
        Timber.d("Resolving browse source via API config")
        executeApiRequest(apiConfig, routeParams)
      },
    )
  }

  /** Resolve a SearchSource into Track results. */
  private suspend fun resolveSearchSource(source: SearchSource, query: String): Array<Track> {
    return source.match(
      // Callback function
      first = { callback ->
        Timber.d("Resolving search source via callback")
        val promise = callback.invoke(query)
        val innerPromise = promise.await()
        innerPromise.await()
      },
      // API configuration
      second = { apiConfig ->
        Timber.d("Resolving search source via API config")
        executeSearchApiRequest(apiConfig, query)
      },
    )
  }

  /**
   * Resolve a TabsSource into BrowserLink array. Handles the three possible types: static array,
   * callback, or API config.
   */
  private suspend fun resolveTabsSource(source: TabsSource): Array<Track> {
    return source.match(
      // Callback function
      first = { callback ->
        Timber.d("Resolving tabs source via callback")
        callback.invoke().await().await()
      },
      // Static array of BrowserLink
      second = { staticTabs ->
        Timber.d("Resolving tabs source via static array")
        staticTabs
      },
      // API configuration
      third = { apiConfig ->
        Timber.d("Resolving tabs source via API config")
        executeTabsApiRequest(apiConfig)
      },
    )
  }

  /**
   * Execute an API request for browser content. Handles URL parameter substitution, config merging,
   * and transforms.
   */
  private suspend fun executeApiRequest(
    apiConfig: TransformableRequestConfig,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    return withContext(Dispatchers.IO) {
      // 1. Start with base config, apply API config on top
      val baseConfig =
        config.request
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
            Timber.w("HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}")
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
   * Execute an API request for search results. Automatically adds query as { q: query } to request
   * parameters.
   */
  private suspend fun executeSearchApiRequest(
    apiConfig: TransformableRequestConfig,
    query: String,
  ): Array<Track> {
    return withContext(Dispatchers.IO) {
      try {
        // 1. Start with base config
        val baseConfig =
          config.request
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

        // 2. Create a copy of API config with added query parameter
        val searchConfig =
          TransformableRequestConfig(
            transform = apiConfig.transform,
            method = apiConfig.method,
            path = apiConfig.path,
            baseUrl = apiConfig.baseUrl,
            headers = apiConfig.headers,
            query = (apiConfig.query ?: emptyMap()) + mapOf("q" to query),
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

  /**
   * Execute an API request for tabs. Expects the API to return a BrowserList with BrowserLink
   * children.
   */
  private suspend fun executeTabsApiRequest(apiConfig: TransformableRequestConfig): Array<Track> {
    return withContext(Dispatchers.IO) {
      try {
        // 1. Start with base config
        val baseConfig =
          config.request
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

        // 2. Merge configs with default path of '/' for tabs
        val mergedConfig =
          RequestConfigBuilder.mergeConfig(baseConfig, apiConfig, emptyMap<String, String>())

        // 3. Build and execute HTTP request
        val httpRequest = RequestConfigBuilder.buildHttpRequest(mergedConfig)
        val response = httpClient.request(httpRequest)

        response.fold(
          onSuccess = { httpResponse ->
            if (httpResponse.isSuccessful) {
              // 4. Parse response as ResolvedTrack
              val jsonResolvedTrack = json.decodeFromString<JsonResolvedTrack>(httpResponse.body)
              val resolvedTrack = jsonResolvedTrack.toNitro()

              // 5. Extract Track items from the resolved track
              resolvedTrack.children
                ?: throw IllegalStateException(
                  "Expected browsed ResolvedTrack to have a children array"
                )
            } else {
              Timber.w(
                "Tabs HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}"
              )
              emptyArray()
            }
          },
          onFailure = { exception ->
            Timber.e(exception, "Tabs HTTP request failed")
            emptyArray()
          },
        )
      } catch (e: Exception) {
        Timber.e(e, "Error executing tabs API request")
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
 * Configuration object that holds all browser settings. This will be passed from AudioBrowser.kt to
 * contain all the configured sources.
 */
typealias BrowseSource =
  com.margelo.nitro.audiobrowser.Variant__param__BrowserSourceCallbackParam_____Promise_Promise_ResolvedTrack___ResolvedTrack_TransformableRequestConfig

data class BrowserConfig(
  val request: RequestConfig? = null,
  val media: MediaRequestConfig? = null,
  val search: SearchSource? = null,
  val routes: Map<String, BrowserSource>? = null,
  val tabs: TabsSource? = null,
  val browse: BrowseSource? = null,
)
