package com.audiobrowser.browser

import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.audiobrowser.extension.identity
import com.audiobrowser.http.HttpClient
import com.audiobrowser.http.RequestConfigBuilder
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.TrackFactory
import com.audiobrowser.util.artworkOf
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

  // Keyed by id, path and src (see cacheTrack), so one track takes up to three entries.
  private val trackCache = LruCache<String, Track>(3000)

  private val contentCache = LruCache<String, ResolvedTrack>(20)

  // Query and results stored as one pair, so a concurrent reader never matches one query with
  // another query's results.
  @Volatile private var lastSearch: Pair<String, Array<Track>>? = null

  // Track identities: id when non-blank, else src (ADR 0008).
  private var favoriteIds = setOf<String>()

  private var favoriteEnabled: Boolean = false

  @Volatile private var currentNavigationId = 0

  /**
   * Setting a new config re-resolves the request/browse resolver layers and the "view more" title
   * on the next request, and clears the artwork registries: their registrations pin the previous
   * config's JS callback handles.
   */
  var config: BrowserConfig = BrowserConfig()
    set(value) {
      field = value
      layerGeneration += 1
      artworkResolutions.clear()
      onArtworkRegistriesCleared?.invoke()
    }

  // Request/browse resolver layers, resolved once per generation and merged per request. The
  // generation moves on a new config or clearContentCache (invalidateAllContent).
  private var layerGeneration = 0
  private var resolvedLayerGeneration = -1
  private var resolvedRequestLayer: TransformableRequestConfig? = null
  private var resolvedBrowseLayer: TransformableRequestConfig? = null

  // Cached on the same generation, but resolved lazily: only a browse that appends a "view more"
  // row asks for it.
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

  /**
   * Maps produced artwork URIs back to the track and config that made them, so display-time loading
   * (which gets only a URI from Media3) can re-resolve with a size. See
   * [ArtworkResolutionRegistry].
   */
  val artworkResolutions = ArtworkResolutionRegistry()

  /** Sets the favorited track identities (id when non-blank, else src). */
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

  /**
   * Sets `favorited` to true or false, so a track that isn't a favorite still shows an empty heart.
   * A value the source already set wins; a track without an identity is left unset.
   */
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

  // Warns once per page: the check runs again on every onGetChildren serve. Concurrent: those
  // serves run several at once on the IO pool.
  private val promiseWarnedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * Dev diagnostic (ADR 0011): an all-grid page under a browsable handle that declares no
   * `style.display` renders as a list on Android Auto, which takes the layout from the parent-level
   * hint, emitted only when the handle declares it. A handle missing from the cache (cold start
   * straight into the page) skips the check. A mixed page never warns: the parent hint lays out the
   * whole page, so grid would tile its list sections too.
   */
  fun warnIfGridPageLacksPromise(path: String, sections: List<Section>) {
    if (sections.isEmpty() || !sections.all { it.style?.display == StyleDisplay.GRID }) return
    val handle = trackCache.get(path) ?: return
    if (handle.src != null) return // rendered playable: opens no page
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
   * Returns the cached track for a mediaId (id, path or src, or a contextual path's trackId), with
   * favorites re-hydrated, or null when it isn't cached.
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
   * round-trips only the mediaId, so the cache is what remembers which list the row came from.
   */
  fun contextualPathFor(mediaId: String): String? {
    if (BrowserPathHelper.isContextual(mediaId)) return mediaId
    return getCachedTrack(mediaId)?.path?.takeIf { BrowserPathHelper.isContextual(it) }
  }

  /**
   * A cache miss is reachable: a controller can replay a mediaId this process never browsed (a
   * search result after process death). When the mediaId is a playable URL, or the item's
   * requestMetadata carries the playable uri (stamped by TrackFactory), it builds a minimal track
   * from the item's metadata.
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
          // A mediaId distinct from the playable uri is the stable id; keeping it lets the car's
          // now-playing row match.
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

      // A failed search match falls through to plain resolution, never to browse expansion: the
      // user asked for that one result, not a browsed list.
      val mediaId = mediaItems[0].mediaId
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

    // Refusal fails the future: Media3 surfaces an error to the controller and current playback
    // stays untouched, where an empty item list would clear the timeline. Android Auto and
    // Assistant request playback through Media3's legacy path, which passes C.INDEX_UNSET for
    // "the first item": normalize before the guard, or the refusal misses the cars.
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
    // Positional, not indexOf: value equality would map a duplicated track to its first copy.
    val adjustedStartIndex =
      when {
        startIndex == C.INDEX_UNSET -> C.INDEX_UNSET
        startTrack == null -> 0
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
        // Re-key the track cache on a hit too: contextualPathFor must see the most recently
        // browsed container.
        cacheChildren(cached)
        return hydrateChildren(cached)
      }
      Timber.d("Content cache MISS for path='$normalizedPath'")
    }

    val resolvedTrack = resolveUncached(normalizedPath)

    contentCache.put(normalizedPath, resolvedTrack)

    cacheChildren(resolvedTrack)

    return hydrateChildren(resolvedTrack)
  }

  /** @throws IllegalArgumentException when [path] is contextual rather than a container path */
  fun invalidateContentCache(path: String) {
    require(!BrowserPathHelper.isContextual(path)) {
      "invalidateContentCache() expects a container path, not a contextual path: $path"
    }
    contentCache.remove(path)
    Timber.d("Invalidated content cache for path='$path'")
  }

  /**
   * Clears every cached page and the last search, which nothing else evicts, and re-resolves the
   * resolver layers and "view more" title on the next request.
   */
  fun clearContentCache() {
    contentCache.evictAll()
    lastSearch = null
    layerGeneration += 1
    artworkResolutions.clear()
    onArtworkRegistriesCleared?.invoke()
    Timber.d("Cleared all content cache")
  }

  private suspend fun resolveUncached(path: String): ResolvedTrack {
    // With no route matched, the implicit default fetches the path via the request + browse config.
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
    // Before the sections are folded, while each level is still attributable.
    InertStyleDiagnostic.warn(path, resolvedTrack.style, sections)
    // The contextual index is the flat page position: children in section order (ADR 0009).
    var flatIndex = 0
    // One scope for the page: every child's artwork transform launches up front, so a slow section
    // doesn't hold up the ones after it.
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
                // Regenerated on every browse so the path names the container it was browsed in;
                // the flat position breaks ties between duplicate identities.
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

                // No display size at browse time.
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
          // Registers a per-route config only, so display-time resolution reads the current
          // config.artwork rather than a stale global.
          artworkResolutions.register(
            imageSource.uri,
            track,
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

      // The stamped flat index pins which section and which copy was tapped; a stale index falls
      // back to the first identity match. A vanished identity aborts: queueing the changed list
      // would resume the wrong station.
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

  /** Returns the results of the most recent search when [query] matches it, else null. */
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
   * Returns the playable results, or null when there are none. A browsable first result plays its
   * children instead.
   *
   * @throws Exception when the search callback fails; a failed API search returns null
   */
  suspend fun searchPlayable(params: SearchParams): Array<Track>? {
    val searchResults = search(params)
    // Disabled tracks go before the first result is picked, so one can't capture the play.
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

    try {
      // Search results skip validateTrack, so one without a path or src is dropped here:
      // TrackFactory.toMedia3 needs one.
      val searchResults =
        resolveSearch(params)
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

      lastSearch = params.query to searchResults

      cacheChildren(searchResolvedTrack)
      Timber.d(
        "Cached search results for query: ${params.query} with ${searchResults.size} results"
      )

      return searchResolvedTrack
    } catch (e: Exception) {
      // Propagates, like iOS `search`: a caller must tell a failed search from an empty one.
      Timber.e(e, "Error during search for query: ${params.query}")
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

  /**
   * Called whenever [artworkResolutions] and the browse-artwork registry are cleared. The caller
   * clears any [BrowseArtworkRegistry] it owns.
   */
  fun setOnArtworkRegistriesCleared(callback: () -> Unit) {
    onArtworkRegistriesCleared = callback
  }

  /** Resolves the tabs once; later calls return the first result. */
  suspend fun queryTabs(): Array<Track> {
    this.tabs?.let {
      return it
    }

    Timber.d("Getting navigation tabs")

    val tabs = resolveTabs()

    tabs.forEachIndexed { index, tab ->
      validateTrack(tab, "Tab")
      Timber.d("[TABS] Tab[$index] '${tab.title}': path=${tab.path}")
    }

    this.tabs = tabs
    return tabs
  }

  companion object {
    internal const val DEFAULT_ROUTE_PATH = "__default__"

    internal const val TABS_ROUTE_PATH = "__tabs__"

    internal const val SEARCH_ROUTE_PATH = "__search__"

    // English, like defaultFormattedError's copy: it still resolves when the JS runtime is gone.
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
      // Nitro flattens `(ResolvedTrack | BrowseError) | Promise<BrowseResult>` into three arms.
      return callback
        .invoke(param)
        .await()
        .match(
          first = { resolvedTrack -> resolvedTrack },
          second = { browseError -> throw CallbackException(browseError.error) },
          third = { promise ->
            promise
              .await()
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
    // The TS layer wraps the thunk in Promise.resolve: await the bridge promise, then the JS one.
    return resolver.invoke().await().await()
  }

  // No in-flight dedupe: concurrent first requests may both resolve, which is harmless, and a
  // resolver that throws caches nothing, so the next request retries.
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
   * The title of the "view more" row appended to a pathed section where the header can't be tapped
   * (Android Auto). Re-read once per generation, so invalidateAllContent picks up a new language.
   * Falls back to [DEFAULT_VIEW_MORE_TITLE] when unset, blank or throwing.
   */
  internal suspend fun viewMoreTitle(): String {
    val generation = layerGeneration
    if (resolvedViewMoreTitleGeneration == generation) return resolvedViewMoreTitle
    val title =
      try {
        config.viewMoreTitle?.invoke()?.await()?.takeIf { it.isNotBlank() }
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
   * [path] seeds the base because no layer's static path ever applies; [initialQuery] seeds it
   * because a layer with a transform receives only the base, so its static query never applies.
   * Mirrors iOS `buildApiRequest`.
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
      // apiConfig is null for the implicit default.
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

        // No browse layer and no route: search is its own kind.
        val httpRequest =
          buildApiRequest(
            kindConfig = apiConfig,
            routeConfig = null,
            // A layer's static path never applies, so the search config's path seeds the base.
            path = apiConfig.path,
            params = emptyMap(),
            initialQuery = searchQueryParams,
          )
        val response = httpClient.request(httpRequest)

        response.fold(
          onSuccess = { httpResponse ->
            if (httpResponse.isSuccessful) {
              // A bare Track array, unlike browse's page object.
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

/** Mirrors NativeBrowserConfiguration from JS. */
data class BrowserConfig(
  val request: TransformableRequestConfig? = null,
  // Resolved once per content generation and merged per request. When set, it replaces `request`,
  // which is then ignored (the JS bridge sets only one of the two).
  val requestResolver:
    Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____? =
    null,
  val browse: TransformableRequestConfig? = null,
  val browseResolver:
    Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____? =
    null,
  val media: MediaRequestConfig? = null,
  val artwork: ArtworkRequestConfig? = null,
  // Falls back to `artwork` when null.
  val nowPlayingArtwork: ArtworkRequestConfig? = null,
  // Includes the special __tabs__, __search__ and __default__ routes.
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
