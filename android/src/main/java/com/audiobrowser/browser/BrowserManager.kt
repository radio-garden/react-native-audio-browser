package com.audiobrowser.browser

import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.audiobrowser.extension.identity
import com.audiobrowser.http.HttpClient
import com.audiobrowser.http.RequestConfigBuilder
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.FALLBACK_TIMEOUT
import com.audiobrowser.util.TrackFactory
import com.audiobrowser.util.artworkOf
import com.audiobrowser.util.awaitCancellable
import com.audiobrowser.util.awaitNested
import com.audiobrowser.util.awaitOrTimeout
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

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
  private var onArtworkRegistriesCleared: (() -> Unit)? = null

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
      if (value != null && !value.contentEquals(previous)) {
        onTabsChanged?.invoke(value)
      } else if (value == null && previous != null) {
        onTabsChanged?.invoke(emptyArray())
      }
    }

  private val trackCache = LruCache<String, Track>(3000)

  private val contentCache = LruCache<String, ResolvedTrack>(20)

  // Moves on clearContentCache, and [pageGenerations] on invalidateContentCache, so a fetch that
  // outlives either caches nothing. Browses run on IO threads and clears on the JS thread: each
  // check and its write hold [cacheLock].
  @Volatile private var contentGeneration = 0
  private val pageGenerations = HashMap<String, Int>()
  private var tabsGeneration = -1
  private val cacheLock = Any()

  // One pair, so a concurrent reader never matches one query with another query's results.
  @Volatile private var lastSearch: Pair<String, Array<Track>>? = null

  private var favoriteIds = setOf<String>()

  private var favoriteEnabled: Boolean = false

  @Volatile private var currentNavigationId = 0

  /**
   * Setting a new config re-resolves the request/browse layers and the "view more" title on the
   * next request, and clears the artwork registries, whose entries hold the previous config's JS
   * callbacks.
   */
  var config: BrowserConfig = BrowserConfig()
    set(value) {
      field = value
      layerGeneration += 1
      artworkResolutions.clear()
      onArtworkRegistriesCleared?.invoke()
    }

  private var layerGeneration = 0
  private var resolvedLayerGeneration = -1
  private var resolvedRequestLayer: TransformableRequestConfig? = null
  private var resolvedBrowseLayer: TransformableRequestConfig? = null

  private var resolvedViewMoreTitleGeneration = -1
  private var resolvedViewMoreTitle = DEFAULT_VIEW_MORE_TITLE

  internal val layerGenerationForTest: Int
    get() = layerGeneration

  internal val resolvedLayerGenerationForTest: Int
    get() = resolvedLayerGeneration

  internal val resolvedRequestLayerForTest: TransformableRequestConfig?
    get() = resolvedRequestLayer

  internal val resolvedBrowseLayerForTest: TransformableRequestConfig?
    get() = resolvedBrowseLayer

  val artworkResolutions = ArtworkResolutionRegistry()

  fun setFavorites(favorites: List<String>) {
    favoriteIds = favorites.toSet()
    Timber.d("Set ${favoriteIds.size} favorite IDs")
  }

  fun setFavoriteEnabled(enabled: Boolean) {
    favoriteEnabled = enabled
  }

  fun updateFavorite(identity: String, favorited: Boolean) {
    favoriteIds =
      if (favorited) {
        favoriteIds + identity
      } else {
        favoriteIds - identity
      }
    Timber.d("Updated favorite for '$identity' to $favorited (total: ${favoriteIds.size})")
  }

  private fun hydrateFavorite(track: Track): Track {
    if (!favoriteEnabled) return track
    if (track.favorited != null) return track
    if (track.identity == null) return track

    return track.copy(favorited = isFavorite(track))
  }

  private fun isFavorite(track: Track): Boolean =
    track.identity?.let { favoriteIds.contains(it) } == true

  // Pages arrive normalized (ADR 0010): `sections` is the only structure to walk.
  private fun hydrateChildren(resolvedTrack: ResolvedTrack): ResolvedTrack {
    val sections = resolvedTrack.sections ?: return resolvedTrack
    return resolvedTrack.copy(
      sections =
        sections
          .map { section ->
            section.copy(children = section.children.map { hydrateFavorite(it) }.toTypedArray())
          }
          .toTypedArray()
    )
  }

  private fun cacheTrack(track: Track) {
    track.id?.takeUnless { it.isBlank() }?.let { trackCache.put(it, track) }
    track.path?.let { trackCache.put(it, track) }
    track.src?.let { trackCache.put(it, track) }
  }

  private fun cacheChildren(resolvedTrack: ResolvedTrack) {
    resolvedTrack.flattenedChildren?.forEach { cacheTrack(it) }
  }

  private val promiseWarnedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * Dev diagnostic (ADR 0011): Android Auto takes a page's layout from the parent-level hint, which
   * is emitted only when the browsable handle declares `style.display`, so an all-grid page under a
   * handle without one renders as a list. A handle missing from the cache skips the check.
   */
  fun warnIfGridPageLacksPromise(path: String, sections: List<Section>) {
    if (sections.isEmpty() || !sections.all { it.style?.display == StyleDisplay.GRID }) return
    val handle = trackCache.get(path) ?: return
    if (handle.src != null) return
    if (handle.style?.display == null && promiseWarnedPaths.add(path)) {
      Timber.w(
        "Page '%s' serves grid sections, but its browsable handle declares no style.display — " +
          "Android Auto's parent-level hint was not emitted, so this page renders as a list " +
          "there. Declare `style: { display: 'grid' }` on the handle track.",
        path,
      )
    }
  }

  /**
   * The cached track for a mediaId (id, path or src, or a contextual path's track id), with
   * favorites re-hydrated, or null.
   */
  fun getCachedTrack(mediaId: String): Track? {
    trackCache.get(mediaId)?.let { track ->
      val hydratedTrack = hydrateFavorite(track)
      Timber.d("Cache HIT for mediaId='$mediaId' → '${track.title}'")
      return hydratedTrack
    }

    val trackId = BrowserPathHelper.extractTrackId(mediaId)
    if (trackId != null) {
      trackCache.get(trackId)?.let { track ->
        val hydratedTrack = hydrateFavorite(track)
        Timber.d("Cache HIT (extracted identity) for mediaId='$mediaId' → '${track.title}'")
        return hydratedTrack
      }
    }

    Timber.w("Cache MISS for mediaId='$mediaId'")
    return null
  }

  /**
   * The contextual path to expand a queue from, or null. A stable-id mediaId resolves through the
   * track cache to the container it was most recently browsed in: a legacy car controller
   * round-trips only the mediaId.
   */
  fun contextualPathFor(mediaId: String): String? {
    if (BrowserPathHelper.isContextual(mediaId)) return mediaId
    return getCachedTrack(mediaId)?.path?.takeIf { BrowserPathHelper.isContextual(it) }
  }

  /**
   * A cache miss is reachable: a controller can replay a mediaId this process never browsed. When
   * the mediaId or the item's requestMetadata carries a playable URL, it builds a minimal track.
   *
   * @throws IllegalStateException when the mediaId is not cached and no playable URL is available
   */
  private fun resolveMediaItemToTrack(mediaItem: MediaItem): Track {
    val mediaId = mediaItem.mediaId

    getCachedTrack(mediaId)?.let { cachedTrack ->
      Timber.d("Resolved mediaId='$mediaId' from cache: '${cachedTrack.title}'")
      return cachedTrack
    }

    val fallbackSrc =
      if (mediaId.startsWith("http://") || mediaId.startsWith("https://")) {
        mediaId
      } else {
        mediaItem.requestMetadata.mediaUri?.toString()
      }
    if (fallbackSrc != null) {
      Timber.w("Cache MISS for mediaId='$mediaId' - building minimal track from media item")
      val metadata = mediaItem.mediaMetadata
      return hydrateFavorite(
        Track(
          // The stable id, so the car's now-playing row matches.
          id = mediaId.takeIf { it != fallbackSrc },
          path = null,
          src = fallbackSrc,
          artwork = artworkOf(metadata.artworkUri?.toString()),
          artworkSource = null,
          request = null,
          title = metadata.title?.toString() ?: mediaId,
          subtitle = metadata.artist?.toString(),
          artist = null,
          albumPath = null,
          album = metadata.albumTitle?.toString(),
          description = metadata.description?.toString(),
          genre = metadata.genre?.toString(),
          duration = null,
          style = null,
          disabled = null,
          favorited = null,
          live = null,
          icyCharset = null,
        )
      )
    }

    throw IllegalStateException("MediaItem not found in cache: $mediaId")
  }

  /**
   * Resolves the items `MediaSessionCallback.onSetMediaItems()` receives. A single item with a
   * search query plays the search results from that item; a single contextual item expands to the
   * tapped section's queue. Otherwise each item resolves through the track cache. Disabled tracks
   * are dropped and the start index shifts to match.
   *
   * @throws IllegalStateException when the start track is disabled, every track is disabled, or a
   *   mediaId is neither cached nor a playable URL
   */
  suspend fun resolveMediaItemsForPlayback(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): MediaSession.MediaItemsWithStartPosition {
    if (mediaItems.size == 1) {
      val mediaItem = mediaItems[0]

      val searchQuery = mediaItem.requestMetadata.searchQuery
      if (searchQuery != null) {
        Timber.d("Handling search playback request for query: $searchQuery")

        val searchResults = search(searchQuery)
        val searchTracks =
          searchResults.flattenedChildren?.filterNot { it.disabled == true }?.toTypedArray()

        if (searchTracks != null && searchTracks.isNotEmpty()) {
          val mediaId = mediaItem.mediaId
          val selectedIndex =
            searchTracks.indexOfFirst { track ->
              track.id == mediaId || track.path == mediaId || track.src == mediaId
            }

          if (selectedIndex >= 0) {
            Timber.d(
              "Playing search result at index $selectedIndex of ${searchTracks.size} results"
            )

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
      // A search request asked for that one result, never for the browsed list around it.
      val contextualPath = if (searchQuery == null) contextualPathFor(mediaId) else null

      if (contextualPath != null) {
        Timber.d("Attempting queue expansion for mediaId='$mediaId' via '$contextualPath'")

        val expanded = expandQueueFromContextualPath(contextualPath)

        if (expanded != null) {
          val (tracks, selectedIndex) = expanded

          val expandedMediaItems = tracks.map { track -> TrackFactory.toMedia3(track) }

          return MediaSession.MediaItemsWithStartPosition(
            expandedMediaItems,
            selectedIndex,
            startPositionMs,
          )
        }
      }
    }

    val resolvedTracks = mediaItems.map { resolveMediaItemToTrack(it) }

    // Refusal fails the future, so Media3 reports an error and current playback stays; an empty
    // item
    // list would clear the timeline. Media3's legacy path (Android Auto, Assistant) passes
    // C.INDEX_UNSET for the first item: normalize before the guard, or the refusal misses it.
    val effectiveStartIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
    val startTrack = resolvedTracks.getOrNull(effectiveStartIndex)
    if (startTrack != null && startTrack.disabled == true) {
      Timber.w("Refusing playback of disabled track: ${startTrack.title}")
      throw IllegalStateException("Refusing playback of disabled track: ${startTrack.title}")
    }
    val playableTracks = resolvedTracks.filterNot { it.disabled == true }
    if (playableTracks.isEmpty()) {
      Timber.w("Refusing playback: every requested track is disabled")
      throw IllegalStateException("Refusing playback: every requested track is disabled")
    }
    val adjustedStartIndex =
      when {
        startIndex == C.INDEX_UNSET -> C.INDEX_UNSET
        startTrack == null -> 0
        // Positional: indexOf would map a duplicated track to its first copy.
        else -> resolvedTracks.take(effectiveStartIndex).count { it.disabled != true }
      }

    val resolvedMediaItems = playableTracks.map { track -> TrackFactory.toMedia3(track) }

    return MediaSession.MediaItemsWithStartPosition(
      resolvedMediaItems,
      adjustedStartIndex,
      startPositionMs,
    )
  }

  private fun validateTrack(track: Track, context: String) {
    if (track.path == null && track.src == null) {
      throw IllegalStateException(
        "$context must have either 'path' or 'src' property for stable identification. Track: ${track.title}"
      )
    }
  }

  /** Resolves the page at [path]; a contextual path resolves its parent container. */
  suspend fun resolve(path: String, useCache: Boolean = true): ResolvedTrack {
    Timber.d("=== RESOLVE: path='$path' (useCache=$useCache) ===")

    val normalizedPath = BrowserPathHelper.stripTrackId(path)
    if (normalizedPath != path) {
      Timber.d("Stripped __trackId from contextual path: '$normalizedPath'")
    }

    if (useCache) {
      contentCache.get(normalizedPath)?.let { cached ->
        Timber.d("Content cache HIT for path='$normalizedPath'")
        // Re-keys the track cache, so contextualPathFor sees the most recently browsed container.
        cacheChildren(cached)
        return hydrateChildren(cached)
      }
      Timber.d("Content cache MISS for path='$normalizedPath'")
    }

    val generation = synchronized(cacheLock) { pageGeneration(normalizedPath) }
    val resolvedTrack = resolveUncached(normalizedPath)

    synchronized(cacheLock) {
      if (generation == pageGeneration(normalizedPath)) {
        contentCache.put(normalizedPath, resolvedTrack)
      }
    }

    cacheChildren(resolvedTrack)

    return hydrateChildren(resolvedTrack)
  }

  /** @throws IllegalArgumentException when [path] is contextual rather than a container path */
  fun invalidateContentCache(path: String) {
    require(!BrowserPathHelper.isContextual(path)) {
      "invalidateContentCache() expects a container path, not a contextual path: $path"
    }
    synchronized(cacheLock) {
      pageGenerations[path] = (pageGenerations[path] ?: 0) + 1
      contentCache.remove(path)
    }
    Timber.d("Invalidated content cache for path='$path'")
  }

  private fun pageGeneration(path: String) = contentGeneration to pageGenerations[path]

  /**
   * Clears every cached page and the last search, and re-resolves the tabs, request layers and
   * "view more" title on the next request. A fetch in flight caches nothing.
   */
  fun clearContentCache() {
    synchronized(cacheLock) {
      contentGeneration += 1
      pageGenerations.clear()
      contentCache.evictAll()
      lastSearch = null
    }
    layerGeneration += 1
    artworkResolutions.clear()
    onArtworkRegistriesCleared?.invoke()
    Timber.d("Cleared all content cache")
  }

  private suspend fun resolveUncached(path: String): ResolvedTrack {
    val match = config.routes?.let { findBestRouteMatch(path, it) }

    val resolvedTrack: ResolvedTrack
    val effectiveArtworkConfig: ArtworkRequestConfig?
    if (match != null) {
      val (routeEntry, routeParams) = match
      Timber.d("Matched route: ${routeEntry.path} with params: $routeParams")
      resolvedTrack = resolveRouteEntry(routeEntry, path, routeParams)
      effectiveArtworkConfig = routeEntry.artwork ?: config.artwork
    } else {
      Timber.d("No route matched for path: $path — using implicit default")
      resolvedTrack = executeApiRequest(null, path, mapOf("path" to path))
      effectiveArtworkConfig = config.artwork
    }

    val sections = resolvedTrack.normalizedSections ?: return resolvedTrack
    // Before the sections are folded, while each declaration is still attributable to its level.
    InertStyleDiagnostic.warn(path, resolvedTrack.style, sections)
    // The contextual index is the flat page position, children in section order (ADR 0009).
    var flatIndex = 0
    val transformedSections = coroutineScope {
      sections
        .map { section ->
          val base = flatIndex
          flatIndex += section.children.size
          section to
            section.children.mapIndexed { offset, track ->
              async {
                val index = base + offset
                validateTrack(track, "Child track")

                var transformedTrack = track

                val trackIdentity = track.identity
                if (track.src != null && trackIdentity != null) {
                  val contextualPath = BrowserPathHelper.build(path, trackIdentity, index)
                  transformedTrack = transformedTrack.copy(path = contextualPath)

                  Timber.d(
                    "[$path] Child[$index] '${track.title}': Playable, contextualPath=$contextualPath (identity=$trackIdentity)"
                  )
                } else {
                  Timber.d(
                    "[$path] Child[$index] '${track.title}': Browsable with path=${track.path}"
                  )
                }

                transformArtworkUrl(
                  transformedTrack,
                  effectiveArtworkConfig,
                  path,
                  index,
                  ImageContext(null, null),
                )
              }
            }
        }
        .map { (section, transformedChildren) ->
          section.copy(children = transformedChildren.awaitAll().toTypedArray())
        }
    }

    return resolvedTrack.copy(sections = transformedSections.toTypedArray(), children = null)
  }

  private suspend fun transformArtworkUrl(
    track: Track,
    artworkConfig: ArtworkRequestConfig?,
    path: String,
    index: Int,
    imageContext: ImageContext? = null,
  ): Track {
    if (artworkConfig == null && track.artwork == null) {
      return track
    }

    return try {
      val imageSource = resolveArtworkUrl(track, artworkConfig, imageContext)

      when {
        imageSource == null -> {
          Timber.d(
            "[$path] Child[$index] '${track.title}': Artwork resolver returned null, no artworkSource"
          )
          track.copy(artworkSource = null)
        }
        else -> {
          Timber.d("[$path] Child[$index] '${track.title}': artworkSource set: ${imageSource.uri}")
          artworkResolutions.register(
            imageSource.uri,
            track,
            // Only a per-route config, so display-time resolution reads the current config.artwork.
            artworkConfig?.takeIf { it !== config.artwork },
          )
          track.copy(artworkSource = imageSource)
        }
      }
    } catch (e: Exception) {
      Timber.e(
        e,
        "[$path] Child[$index] '${track.title}': Artwork transform failed, clearing artworkSource",
      )
      track.copy(artworkSource = null)
    }
  }

  /**
   * Expands a contextual path into the playable, enabled tracks of the tapped section (ADR 0006)
   * and the start index, or only the tapped track when `singleTrack` is set. Returns null when the
   * path is not contextual, the tapped identity is no longer on the page or is disabled, or
   * resolution fails; the caller then plays the stored single track.
   */
  suspend fun expandQueueFromContextualPath(contextualPath: String): Pair<Array<Track>, Int>? {
    val trackId = BrowserPathHelper.extractTrackId(contextualPath) ?: return null

    Timber.d("Expanding queue from contextual path: $contextualPath (trackId=$trackId)")

    try {
      val parentPath = BrowserPathHelper.stripTrackId(contextualPath)
      val parentResolvedTrack = resolve(parentPath)
      val sections = parentResolvedTrack.normalizedSections

      if (sections.isNullOrEmpty()) {
        Timber.w("Parent has no children, cannot expand queue")
        return null
      }

      val tappedIndex = BrowserPathHelper.extractIndex(contextualPath)
      val scoped = SectionScope.scoped(sections, trackId, tappedIndex) ?: return null
      val sectionTracks = scoped.section.children.toList()
      val tappedOffset = scoped.tappedOffset

      val playableTracks =
        sectionTracks.filter { track -> track.src != null && track.disabled != true }

      if (playableTracks.isEmpty()) {
        Timber.w("Parent has no playable tracks, cannot expand queue")
        return null
      }

      val selectedIndex =
        if (
          tappedOffset != null &&
            sectionTracks[tappedOffset].src != null &&
            sectionTracks[tappedOffset].disabled != true
        ) {
          sectionTracks.take(tappedOffset + 1).count { it.src != null && it.disabled != true } - 1
        } else {
          playableTracks.indexOfFirst { track -> track.identity == trackId }
        }

      if (selectedIndex < 0) {
        Timber.w("Track with identity='$trackId' not found in playable children")
        return null
      }

      if (config.singleTrack) {
        Timber.d("singleTrack=true - returning single track at index $selectedIndex")
        return Pair(arrayOf(playableTracks[selectedIndex]), 0)
      }

      Timber.d(
        "singleTrack=false - returning ${playableTracks.size} playable tracks (from ${sectionTracks.size} in section), starting at index $selectedIndex"
      )
      return Pair(playableTracks.toTypedArray(), selectedIndex)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.e(e, "Error expanding queue from contextual path: $contextualPath")
      return null
    }
  }

  /**
   * Sets the path and clears the content at once, then resolves. Only the latest navigation or
   * refresh publishes its content; an overtaken one still returns its result.
   */
  suspend fun navigate(path: String): ResolvedTrack {
    Timber.d("Navigating to path: $path")

    val navigationId = ++currentNavigationId

    this.path = path
    this.content = null
    val content = resolve(path)

    if (navigationId == currentNavigationId) {
      this.content = content
    }
    return content
  }

  /** Re-resolves the current path, bypassing the cache. Errors are logged, never thrown. */
  suspend fun refresh() {
    val navigationId = ++currentNavigationId

    val currentPath = path
    Timber.d("Refreshing content for path: $currentPath")

    try {
      contentCache.remove(currentPath)
      val content = resolve(currentPath, useCache = false)

      if (navigationId == currentNavigationId) {
        this.content = content
      }
    } catch (e: Exception) {
      Timber.e(e, "Error refreshing content for path: $currentPath")
    }
  }

  fun getCachedSearchResults(query: String): Array<Track>? {
    val (cachedQuery, cachedResults) = lastSearch ?: return null
    if (query != cachedQuery) return null
    return cachedResults.map { hydrateFavorite(it) }.toTypedArray()
  }

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
        reference = MediaReference.UNKNOWN,
      )
    )
  }

  /**
   * The playable results, or null when there are none. A browsable first result plays its children
   * instead.
   *
   * @throws Exception when the search callback fails; a failed API search returns null
   */
  suspend fun searchPlayable(params: SearchParams): Array<Track>? {
    val searchResults = search(params)
    // Disabled tracks go first, so one can't become the first result.
    val tracks = searchResults.flattenedChildren?.filter { it.disabled != true }?.toTypedArray()

    if (tracks.isNullOrEmpty()) {
      return null
    }

    val firstResult = tracks[0]

    val firstResultPath = firstResult.path
    val tracksToFilter =
      if (firstResult.src == null && firstResultPath != null) {
        Timber.d("First search result is browsable-only, resolving: $firstResultPath")
        val resolvedTrack = resolve(firstResultPath)
        resolvedTrack.flattenedChildren
          ?.filter { it.src != null && it.disabled != true }
          ?.takeIf { it.isNotEmpty() }
          ?.toTypedArray() ?: tracks
      } else {
        tracks
      }

    return tracksToFilter.filter { it.src != null }.takeIf { it.isNotEmpty() }?.toTypedArray()
  }

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
        reference = MediaReference.UNKNOWN,
      )
    )
  }

  /**
   * Runs a fresh search and keeps its results for [getCachedSearchResults].
   *
   * @throws Exception when the search callback fails; a failed API search yields no results
   */
  suspend fun search(params: SearchParams): ResolvedTrack {
    Timber.d("Executing fresh search for: ${params.query} (mode=${params.mode})")

    val searchPath = BrowserPathHelper.createSearchPath(params.query)
    val generation = contentGeneration

    try {
      val searchResults =
        resolveSearch(params)
          // Search results skip validateTrack, and TrackFactory.toMedia3 needs a path or src.
          .filter { track ->
            val valid = track.path != null || track.src != null
            if (!valid) {
              Timber.w("Dropping search result without path or src: '${track.title}'")
            }
            valid
          }
          .toTypedArray()

      val searchResolvedTrack =
        ResolvedTrack(
          id = null,
          path = searchPath,
          title = "Search: ${params.query}",
          sections = arrayOf(untitledSection(searchResults)),
          children = null,
          carPlaySiriListButton = null,
          artwork = null,
          artworkSource = null,
          request = null,
          artist = null,
          albumPath = null,
          description = null,
          subtitle = null,
          album = null,
          genre = null,
          duration = null,
          src = null,
          style = null,
          disabled = null,
          favorited = null,
          live = null,
          icyCharset = null,
        )

      synchronized(cacheLock) {
        if (generation == contentGeneration) lastSearch = params.query to searchResults
      }

      cacheChildren(searchResolvedTrack)
      Timber.d(
        "Cached search results for query: ${params.query} with ${searchResults.size} results"
      )

      return searchResolvedTrack
    } catch (e: Exception) {
      Timber.e(e, "Error during search for query: ${params.query}")
      // Like iOS: a caller must tell a failed search from an empty one.
      throw e
    }
  }

  fun getPath(): String {
    return path
  }

  fun getContent(): ResolvedTrack? {
    return content
  }

  fun getTabs(): Array<Track>? {
    return tabs
  }

  fun setOnPathChanged(callback: (String) -> Unit) {
    onPathChanged = callback
  }

  fun setOnContentChanged(callback: (ResolvedTrack?) -> Unit) {
    onContentChanged = callback
  }

  fun setOnTabsChanged(callback: (Array<Track>) -> Unit) {
    onTabsChanged = callback
  }

  /** The caller clears any [BrowseArtworkRegistry] it owns. */
  fun setOnArtworkRegistriesCleared(callback: () -> Unit) {
    onArtworkRegistriesCleared = callback
  }

  /**
   * Resolves the tabs once per [clearContentCache]; later calls return that result. The previous
   * tabs stay until new ones resolve.
   */
  suspend fun queryTabs(): Array<Track> {
    val generation = contentGeneration
    synchronized(cacheLock) { tabs.takeIf { tabsGeneration == generation } }
      ?.let {
        return it
      }

    Timber.d("Getting navigation tabs")

    val tabs = resolveTabs()

    tabs.forEachIndexed { index, tab ->
      validateTrack(tab, "Tab")
      Timber.d("[TABS] Tab[$index] '${tab.title}': path=${tab.path}")
    }

    synchronized(cacheLock) {
      if (generation == contentGeneration) {
        this.tabs = tabs
        tabsGeneration = generation
      }
    }
    return tabs
  }

  companion object {
    internal const val DEFAULT_ROUTE_PATH = "__default__"

    internal const val TABS_ROUTE_PATH = "__tabs__"

    internal const val SEARCH_ROUTE_PATH = "__search__"

    // English: it must resolve when the JS runtime is gone.
    internal const val DEFAULT_VIEW_MORE_TITLE = "View more"
  }

  private fun findBestRouteMatch(
    path: String,
    routes: Array<NativeRouteEntry>,
  ): Pair<NativeRouteEntry, Map<String, String>>? {
    val routeMap = routes.filter { it.path != DEFAULT_ROUTE_PATH }.associateBy { it.path }

    router.findBestMatch(path, routeMap)?.let { (routePattern, match) ->
      val routeEntry = routeMap[routePattern]!!
      return Pair(routeEntry, match.params)
    }

    routes
      .find { it.path == DEFAULT_ROUTE_PATH }
      ?.let { defaultRoute ->
        return Pair(defaultRoute, emptyMap())
      }

    return null
  }

  private suspend fun resolveRouteEntry(
    entry: NativeRouteEntry,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    entry.browseCallback?.let { callback ->
      Timber.d("Resolving route via callback")
      val param = BrowserSourceCallbackParam(path, routeParams)
      return callback
        .invoke(param)
        .awaitCancellable()
        .match(
          first = { resolvedTrack -> resolvedTrack },
          second = { browseError -> throw CallbackException(browseError.error) },
          third = { promise ->
            promise
              .awaitCancellable()
              .match(
                first = { resolvedTrack -> resolvedTrack },
                second = { browseError -> throw CallbackException(browseError.error) },
              )
          },
        )
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

  private suspend fun resolveSearch(params: SearchParams): Array<Track> {
    val routes = config.routes ?: return emptyArray()

    val searchEntry = routes.find { it.path == SEARCH_ROUTE_PATH }
    if (searchEntry == null) {
      Timber.w("No search route configured")
      return emptyArray()
    }

    searchEntry.searchCallback?.let { callback ->
      Timber.d("Resolving search via callback")
      return callback.invoke(params).awaitNested()
    }

    searchEntry.searchConfig?.let { apiConfig ->
      Timber.d("Resolving search via API config")
      return executeSearchApiRequest(apiConfig, params)
    }

    Timber.w("Search route has no callback or config")
    return emptyArray()
  }

  private suspend fun resolveTabs(): Array<Track> {
    val routes = config.routes ?: return emptyArray()

    val tabsEntry = routes.find { it.path == TABS_ROUTE_PATH }
    if (tabsEntry == null) {
      Timber.d("No tabs route configured")
      return emptyArray()
    }

    Timber.d("Resolving tabs via route entry")
    val resolvedTrack = resolveRouteEntry(tabsEntry, TABS_ROUTE_PATH, emptyMap())

    return resolvedTrack.flattenedChildren?.toTypedArray() ?: emptyArray()
  }

  private suspend fun resolveLayer(
    staticConfig: TransformableRequestConfig?,
    resolver: Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____?,
  ): TransformableRequestConfig? {
    if (resolver == null) return staticConfig
    return resolver.invoke().awaitNested()
  }

  internal suspend fun ensureLayersResolved() {
    if (resolvedLayerGeneration == layerGeneration) return
    val generation = layerGeneration
    val req = resolveLayer(config.request, config.requestResolver)
    val brw = resolveLayer(config.browse, config.browseResolver)
    if (generation != layerGeneration) return
    resolvedRequestLayer = req
    resolvedBrowseLayer = brw
    resolvedLayerGeneration = generation
  }

  /**
   * The request layer for the current generation. Media URLs and artwork read it through here,
   * never `config.request`, which a resolver-only config leaves unset.
   */
  internal suspend fun resolvedRequestConfig(): TransformableRequestConfig? {
    ensureLayersResolved()
    return resolvedRequestLayer
  }

  /**
   * The title of the "view more" row appended to a pathed section whose header can't be tapped
   * (Android Auto). Re-read once per generation, so clearContentCache picks up a new language.
   * Falls back to [DEFAULT_VIEW_MORE_TITLE] when unset, blank, throwing or outrunning
   * [FALLBACK_TIMEOUT].
   */
  internal suspend fun viewMoreTitle(): String {
    val generation = layerGeneration
    if (resolvedViewMoreTitleGeneration == generation) return resolvedViewMoreTitle
    val title =
      try {
        config.viewMoreTitle?.invoke()?.awaitOrTimeout()?.takeIf { it.isNotBlank() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.e(e, "viewMoreTitle callback failed; falling back to the default")
        null
      } ?: DEFAULT_VIEW_MORE_TITLE
    if (generation != layerGeneration) return title
    resolvedViewMoreTitle = title
    resolvedViewMoreTitleGeneration = generation
    return title
  }

  /**
   * Layers request → kind (browse/search) → route; each transform receives the previous output.
   * [path] and [initialQuery] seed the base, as no layer's static path ever applies and a layer
   * with a transform receives only the base. Mirrors iOS `buildApiRequest`.
   *
   * @throws ContentNotFoundException when no layer supplies a baseUrl
   */
  internal suspend fun buildApiRequest(
    kindConfig: TransformableRequestConfig?,
    routeConfig: TransformableRequestConfig?,
    path: String?,
    params: Map<String, String>,
    initialQuery: Map<String, String>? = null,
  ): HttpClient.HttpRequest {
    ensureLayersResolved()

    var merged =
      RequestConfig(
        method = null,
        path = path,
        baseUrl = null,
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )
    resolvedRequestLayer?.let { merged = RequestConfigBuilder.mergeConfig(merged, it, params) }
    if (!initialQuery.isNullOrEmpty()) {
      merged = merged.copy(query = (merged.query ?: emptyMap()) + initialQuery)
    }
    kindConfig?.let { merged = RequestConfigBuilder.mergeConfig(merged, it, params) }
    routeConfig?.let { merged = RequestConfigBuilder.mergeConfig(merged, it, params) }

    if (merged.baseUrl.isNullOrBlank()) {
      throw ContentNotFoundException(path ?: "")
    }
    return RequestConfigBuilder.buildHttpRequest(merged)
  }

  private suspend fun executeApiRequest(
    apiConfig: TransformableRequestConfig?,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    return withContext(Dispatchers.IO) {
      ensureLayersResolved()
      val httpRequest = buildApiRequest(resolvedBrowseLayer, apiConfig, path, routeParams)
      val response = httpClient.request(httpRequest)

      response.fold(
        onSuccess = { httpResponse ->
          if (httpResponse.isSuccessful) {
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

  private suspend fun executeSearchApiRequest(
    apiConfig: TransformableRequestConfig,
    params: SearchParams,
  ): Array<Track> {
    return withContext(Dispatchers.IO) {
      try {
        val searchQueryParams = buildMap {
          put("q", params.query)
          params.mode?.let { put("mode", it.toString().lowercase()) }
          if (params.reference == MediaReference.MY) put("reference", "my")
          params.genre?.let { put("genre", it) }
          params.artist?.let { put("artist", it) }
          params.album?.let { put("album", it) }
          params.title?.let { put("title", it) }
          params.playlist?.let { put("playlist", it) }
        }

        val httpRequest =
          buildApiRequest(
            kindConfig = apiConfig,
            routeConfig = null,
            path = apiConfig.path,
            params = emptyMap(),
            initialQuery = searchQueryParams,
          )
        val response = httpClient.request(httpRequest)

        response.fold(
          onSuccess = { httpResponse ->
            if (httpResponse.isSuccessful) {
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

class ContentNotFoundException(val path: String) : Exception("Content not found")

class HttpStatusException(val statusCode: Int, message: String) : Exception(message)

class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CallbackException(message: String) : Exception(message)

/** Mirrors `NativeBrowserConfiguration` from TS. */
data class BrowserConfig(
  val request: TransformableRequestConfig? = null,
  // When set, replaces `request`: the bridge sets only one of the two.
  val requestResolver:
    Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____? =
    null,
  val browse: TransformableRequestConfig? = null,
  val browseResolver:
    Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____? =
    null,
  val media: MediaRequestConfig? = null,
  val artwork: ArtworkRequestConfig? = null,
  val nowPlayingArtwork: ArtworkRequestConfig? = null,
  val routes: Array<NativeRouteEntry>? = null,
  val singleTrack: Boolean = false,
  val androidControllerOfflineError: Boolean = true,
  val viewMoreTitle: (() -> Promise<String>)? = null,
) {
  val hasSearch: Boolean
    get() {
      val searchEntry = routes?.find { it.path == BrowserManager.SEARCH_ROUTE_PATH }
      return searchEntry?.searchCallback != null || searchEntry?.searchConfig != null
    }
}
