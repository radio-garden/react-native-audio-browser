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
      // Arrays need contentEquals for comparison
      if (value != null && !value.contentEquals(previous)) {
        onTabsChanged?.invoke(value)
      } else if (value == null && previous != null) {
        onTabsChanged?.invoke(emptyArray())
      }
    }

  // LRU cache for individual tracks - keyed by both path and src for O(1) lookup
  private val trackCache = LruCache<String, Track>(3000)

  // LRU cache for resolved content - keyed by path
  // Keeps recently visited paths cached for fast back navigation and tab switching
  // Invalidated via invalidateContentCache() when content changes
  private val contentCache = LruCache<String, ResolvedTrack>(20)

  // Cache for the most recent search - the query string and its results stored as one pair, so a
  // concurrent reader never sees one query matched with another query's results.
  @Volatile private var lastSearch: Pair<String, Array<Track>>? = null

  // Set of favorited track identities (id when non-blank, else src — see Track.identity)
  private var favoriteIds = setOf<String>()

  // Whether favoriting is enabled, propagated from the player's `favorite` capability.
  // false = favoriting disabled (no row hearts). Set via setFavoriteEnabled.
  private var favoriteEnabled: Boolean = false

  // Navigation tracking to prevent race conditions
  @Volatile private var currentNavigationId = 0

  /**
   * Browser configuration containing routes, search, tabs, and request settings. This can be
   * updated dynamically when the configuration changes.
   *
   * Setting a new config bumps [layerGeneration] so the cached request/browse resolver layers are
   * re-resolved on the next request (the resolvers may close over config-derived state).
   */
  var config: BrowserConfig = BrowserConfig()
    set(value) {
      field = value
      layerGeneration += 1
      // Registered artwork resolutions pin configs (and their JS callback handles)
      // from the previous configuration — never resolve through them again.
      artworkResolutions.clear()
      onArtworkRegistriesCleared?.invoke()
    }

  // Resolver-layer caching. The request/browse layers may be resolver thunks
  // (config.requestResolver / config.browseResolver) resolved once per *content generation*:
  // re-resolved when content is invalidated (clearContentCache, from invalidateAllContent) or when
  // a new config is set, cached, and merged per request.
  private var layerGeneration = 0
  private var resolvedLayerGeneration = -1
  private var resolvedRequestLayer: TransformableRequestConfig? = null
  private var resolvedBrowseLayer: TransformableRequestConfig? = null

  /** Test-only accessors for the resolver-layer cache state (see ensureLayersResolved). */
  internal val layerGenerationForTest: Int
    get() = layerGeneration

  internal val resolvedLayerGenerationForTest: Int
    get() = resolvedLayerGeneration

  internal val resolvedRequestLayerForTest: TransformableRequestConfig?
    get() = resolvedRequestLayer

  internal val resolvedBrowseLayerForTest: TransformableRequestConfig?
    get() = resolvedBrowseLayer

  /**
   * Maps produced artwork URIs back to (Track, artwork-config kind) so display-time bitmap loading
   * can re-resolve Track-first. See [ArtworkResolutionRegistry].
   */
  val artworkResolutions = ArtworkResolutionRegistry()

  /**
   * Sets the favorited track identities (id when non-blank, else src). Tracks will have their
   * favorited field hydrated based on this list during browsing.
   */
  fun setFavorites(favorites: List<String>) {
    favoriteIds = favorites.toSet()
    Timber.d("Set ${favoriteIds.size} favorite IDs")
  }

  /**
   * Enables or disables favoriting (propagated from the `favorite` capability). false disables
   * row-heart hydration.
   */
  fun setFavoriteEnabled(enabled: Boolean) {
    favoriteEnabled = enabled
  }

  /**
   * Updates the favorite state for a single track identity (id when non-blank, else src). Called
   * when the heart button is tapped in media controllers.
   */
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
   * Hydrates the favorited field on a track based on the favoriteIds set. No-op unless favoriting
   * is enabled (the `favorite` capability). Only tracks with an identity (id or src) are
   * favoritable; the flag is set to true OR false so non-favorited tracks still show an (empty)
   * heart. Doesn't overwrite API-provided values.
   */
  private fun hydrateFavorite(track: Track): Track {
    if (!favoriteEnabled) return track
    // Don't overwrite API-provided favorites
    if (track.favorited != null) return track
    // Only identity-bearing tracks are favoritable
    if (track.identity == null) return track

    return track.copy(favorited = isFavorite(track))
  }

  /** Whether [track]'s identity is in the favorites set. */
  private fun isFavorite(track: Track): Boolean =
    track.identity?.let { favoriteIds.contains(it) } == true

  /**
   * Hydrates favorites on all tracks of a resolved page. Pages reach hydration normalized (ADR
   * 0010), so `sections` is the only structure to walk.
   */
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

  /** Cache a track by id, path, and src for O(1) lookup from any mediaId form. */
  private fun cacheTrack(track: Track) {
    track.id?.takeUnless { it.isBlank() }?.let { trackCache.put(it, track) }
    track.path?.let { trackCache.put(it, track) }
    track.src?.let { trackCache.put(it, track) }
  }

  private fun cacheChildren(resolvedTrack: ResolvedTrack) {
    resolvedTrack.flattenedChildren?.forEach { cacheTrack(it) }
  }

  // Pages already warned about by warnIfGridPageLacksPromise — the mismatch
  // re-observes on every onGetChildren serve, and once is a diagnostic while
  // every-scroll is noise. Concurrent: onGetChildren serves run on the IO
  // pool, several in flight at once.
  private val promiseWarnedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * Dev diagnostic (ADR 0011): a page serving grid sections under a browsable handle that declared
   * no `style.display` promise still renders its parent-level layout as a list on Android Auto —
   * the parent hint is emitted only when declared, never derived from resolved pages and never
   * back-filled. Warns at the one site where the mismatch is observable; a cache miss for the
   * handle (cold start straight into the page) just skips the check.
   *
   * Only an all-grid page warns: the parent-level hint lays out the WHOLE page, so on a mixed page
   * (a teaser shelf above list sections) there is no correct promise to advise — grid would tile
   * the list sections too.
   */
  fun warnIfGridPageLacksPromise(path: String, sections: List<Section>) {
    if (sections.isEmpty() || !sections.all { it.style?.display == StyleDisplay.GRID }) return
    val handle = trackCache.get(path) ?: return
    if (handle.src != null) return // rendered playable — opens no page, promises nothing
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
   * Get a cached Track by mediaId (stable id, path, or src), or null if not cached. Used by Media3
   * to rehydrate MediaItem shells with full track metadata. Re-hydrates favorites in case
   * setFavoriteStates was called after caching.
   */
  fun getCachedTrack(mediaId: String): Track? {
    // Try direct lookup first (matches path or src)
    trackCache.get(mediaId)?.let { track ->
      val hydratedTrack = hydrateFavorite(track)
      Timber.d("Cache HIT for mediaId='$mediaId' → '${track.title}'")
      return hydratedTrack
    }

    // Try extracting the track identity (id when non-blank, else src) from a contextual path —
    // the cache is keyed by id AND src, so either identity form resolves.
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
   * Resolves a tapped mediaId to the contextual path to expand a queue from, or null when there is
   * none. A contextual mediaId is its own; a stable-id mediaId (see TrackFactory.buildMediaItem)
   * resolves through the track cache to the contextual path of the container it was most recently
   * browsed in — a legacy car controller round-trips only the mediaId, so the cache is what
   * remembers which list the row came from.
   */
  fun contextualPathFor(mediaId: String): String? {
    if (BrowserPathHelper.isContextual(mediaId)) return mediaId
    return getCachedTrack(mediaId)?.path?.takeIf { BrowserPathHelper.isContextual(it) }
  }

  /**
   * Resolves a single Media3 MediaItem to a Track. Prefers the track cache (keyed by id, path, and
   * src).
   *
   * A cache miss is legitimately reachable: a controller can replay a mediaId this process never
   * browsed — e.g. a search-result track after process death. When the mediaId is a playable URL,
   * or the item's requestMetadata carries the playable uri (stamped by TrackFactory for stable-id
   * mediaIds, and round-tripped by Media3 controllers), fall back to a minimal track built from the
   * item's own metadata instead of failing playback.
   *
   * @throws IllegalStateException if the mediaId is not cached and no playable URL is available
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
          // A mediaId distinct from the playable uri is the track's stable id —
          // keep it so the item's identity (car now-playing row match) survives.
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
        )
      )
    }

    throw IllegalStateException("MediaItem not found in cache: $mediaId")
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
   * @throws IllegalStateException if a mediaId is neither cached nor a playable URL
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

        // Execute search (will hit cache if already performed). Disabled
        // tracks never play, so they never enter a search-built queue.
        val searchResults = search(searchQuery)
        val searchTracks =
          searchResults.flattenedChildren?.filterNot { it.disabled == true }?.toTypedArray()

        if (searchTracks != null && searchTracks.isNotEmpty()) {
          // Find the selected track in search results (mediaId is the stable id
          // when the track has one, else path/src — see TrackFactory)
          val mediaId = mediaItem.mediaId
          val selectedIndex =
            searchTracks.indexOfFirst { track ->
              track.id == mediaId || track.path == mediaId || track.src == mediaId
            }

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
      // A failed search match falls through to plain resolution, never to browse
      // expansion — the user asked for that one result, not a browsed list.
      val contextualPath = if (searchQuery == null) contextualPathFor(mediaId) else null

      if (contextualPath != null) {
        Timber.d("Attempting queue expansion for mediaId='$mediaId' via '$contextualPath'")

        val expanded = expandQueueFromContextualPath(contextualPath)

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

    // No expansion - resolve from cache (with a minimal-track fallback for replayed mediaIds)
    val resolvedTracks = mediaItems.map { resolveMediaItemToTrack(it) }

    // A disabled track is unavailable — it never plays, even by a stale or
    // replayed mediaId (Track.disabled). Refusal fails the future: Media3
    // surfaces an error to the controller and the current playback stays
    // untouched (an empty item list would clear the live timeline instead).
    //
    // Media3's legacy path — how Android Auto and Assistant actually request
    // playback (onPlayFromMediaId et al.) — passes C.INDEX_UNSET, meaning
    // "the default item", i.e. the first. Normalize before the guard, or the
    // refusal is dead on exactly the path that serves cars.
    val effectiveStartIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
    val startTrack = resolvedTracks.getOrNull(effectiveStartIndex)
    if (startTrack != null && startTrack.disabled == true) {
      Timber.w("Refusing playback of disabled track: ${startTrack.title}")
      throw IllegalStateException("Refusing playback of disabled track: ${startTrack.title}")
    }
    val playableTracks = resolvedTracks.filterNot { it.disabled == true }
    if (playableTracks.isEmpty()) {
      // Belt over the start-item guard: never hand Media3 an empty timeline.
      Timber.w("Refusing playback: every requested track is disabled")
      throw IllegalStateException("Refusing playback: every requested track is disabled")
    }
    // Positional, not indexOf: value equality would alias a duplicated track
    // (the same station twice in a queue) to its first copy. An unset index
    // stays unset — Media3's "default item" semantics pass through as they
    // did before the filter existed.
    val adjustedStartIndex =
      when {
        startIndex == C.INDEX_UNSET -> C.INDEX_UNSET
        startTrack == null -> 0
        else -> resolvedTracks.take(effectiveStartIndex).count { it.disabled != true }
      }

    // Convert to Media3 MediaItems
    val resolvedMediaItems = playableTracks.map { track -> TrackFactory.toMedia3(track) }

    return MediaSession.MediaItemsWithStartPosition(
      resolvedMediaItems,
      adjustedStartIndex,
      startPositionMs,
    )
  }

  /**
   * Validates that a track has either path or src for stable identification. Throws
   * IllegalStateException if validation fails.
   */
  private fun validateTrack(track: Track, context: String) {
    if (track.path == null && track.src == null) {
      throw IllegalStateException(
        "$context must have either 'path' or 'src' property for stable identification. Track: ${track.title}"
      )
    }
  }

  suspend fun resolve(path: String, useCache: Boolean = true): ResolvedTrack {
    Timber.d("=== RESOLVE: path='$path' (useCache=$useCache) ===")

    // Strip __trackId from contextual paths (e.g., "/library/radio?__trackId=song.mp3" →
    // "/library/radio")
    // This allows resolving the parent container for tracks referenced by contextual path
    val normalizedPath = BrowserPathHelper.stripTrackId(path)
    if (normalizedPath != path) {
      Timber.d("Stripped __trackId from contextual path: '$normalizedPath'")
    }

    // Check content cache first
    if (useCache) {
      contentCache.get(normalizedPath)?.let { cached ->
        Timber.d("Content cache HIT for path='$normalizedPath'")
        // Re-key the track cache even on a hit: an id-keyed lookup (stable-id
        // mediaId → contextual path, see contextualPathFor) must reflect the
        // most recently *browsed* container, which a cached re-display
        // otherwise wouldn't re-register.
        cacheChildren(cached)
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
   * Invalidates the content cache for a specific path. Called when content at that path has changed
   * (e.g., via notifyContentChanged).
   *
   * @param path The container path to invalidate (e.g., "/library/radio")
   * @throws IllegalArgumentException if passed a contextual path (contains __trackId)
   */
  fun invalidateContentCache(path: String) {
    require(!BrowserPathHelper.isContextual(path)) {
      "invalidateContentCache() expects a container path, not a contextual path: $path"
    }
    contentCache.remove(path)
    Timber.d("Invalidated content cache for path='$path'")
  }

  /** Clears all cached content. */
  fun clearContentCache() {
    contentCache.evictAll()
    // Bump the layer generation so request/browse resolver thunks are re-resolved on the next
    // request (invalidateAllContent → clearContentCache is the documented re-resolve trigger).
    layerGeneration += 1
    // Invalidated content's artwork resolutions go with it (same staleness rule as the
    // config setter).
    artworkResolutions.clear()
    onArtworkRegistriesCleared?.invoke()
    Timber.d("Cleared all content cache")
  }

  private suspend fun resolveUncached(path: String): ResolvedTrack {
    // Match an explicit route (or the '*' default). With no match, fall back to
    // the implicit default: fetch the path via the request + browse config.
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

    // Normalize to the canonical sectioned shape (children sugar → one untitled
    // section — ADR 0010), then generate contextual paths and transform artwork
    // URLs. The output never carries `children`.
    val sections = resolvedTrack.normalizedSections ?: return resolvedTrack
    // Dev diagnostic (ADR 0011) — before the blocks are folded, while each level is
    // still attributable. Every page passes here whatever its source, and only on a
    // cache miss. No-op outside debug builds.
    InertStyleDiagnostic.warn(path, resolvedTrack.style, sections)
    // The contextual index is the flat page position — children concatenated in
    // section order (ADR 0009/0010).
    var flatIndex = 0
    // One scope for the whole page: every child's transform (which can suspend
    // on artwork resolution) launches up front across all sections, then the
    // sections reassemble — a slow section doesn't serialize the ones after it.
    val transformedSections = coroutineScope {
      sections
        .map { section ->
          val base = flatIndex
          flatIndex += section.children.size
          section to
            section.children.mapIndexed { offset, track ->
              async {
                val index = base + offset
                // Validate that track has stable identifier.
                validateTrack(track, "Child track")

                var transformedTrack = track

                // Generate contextual paths for playable tracks
                // Always regenerate to reflect the current browsing context, not the original
                // context
                // (e.g., a track favorited from an album should use /favorites context when browsed
                // there)
                // The flat page position rides along as a duplicate-identity tie-breaker.
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

                // Transform artwork URL. At browse-time there is no display size info.
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

  /**
   * Transforms a track's artwork via [resolveArtworkUrl]. Populates artworkSource with the
   * transformed ImageSource, keeping artwork unchanged. Handles all edge cases: undefined returns,
   * errors, missing artwork.
   *
   * @param imageContext Optional size context for CDN URL generation (null at browse-time)
   */
  private suspend fun transformArtworkUrl(
    track: Track,
    artworkConfig: ArtworkRequestConfig?,
    path: String,
    index: Int,
    imageContext: ImageContext? = null,
  ): Track {
    // No artwork config and no track.artwork - nothing to transform
    if (artworkConfig == null && track.artwork == null) {
      return track
    }

    return try {
      val imageSource = resolveArtworkUrl(track, artworkConfig, imageContext)

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
          // Remember how this URI was produced so display-time loading (which only
          // gets a URI from Media3) can re-resolve Track-first with a size hint.
          // Register the per-route config only when it isn't the global fallback,
          // so display-time resolution reads the *current* config.artwork.
          artworkResolutions.register(
            imageSource.uri,
            track,
            artworkConfig?.takeIf { it !== config.artwork },
          )
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
   * Expands a contextual path into a queue of playable tracks.
   *
   * Used when navigating to a track to load it with its full album/playlist context. Returns only
   * the selected track if singleTrack is true.
   *
   * @param contextualPath The contextual path (e.g., "/album?__trackId=song.mp3")
   * @return Pair of (tracks array, selected track index), or null if expansion fails
   */
  suspend fun expandQueueFromContextualPath(contextualPath: String): Pair<Array<Track>, Int>? {
    val trackId = BrowserPathHelper.extractTrackId(contextualPath) ?: return null

    Timber.d("Expanding queue from contextual path: $contextualPath (trackId=$trackId)")

    try {
      // Resolve the parent container to get all siblings
      val parentPath = BrowserPathHelper.stripTrackId(contextualPath)
      val parentResolvedTrack = resolve(parentPath)
      val sections = parentResolvedTrack.normalizedSections

      if (sections.isNullOrEmpty()) {
        Timber.w("Parent has no children, cannot expand queue")
        return null
      }

      // Queue scope is the tapped section, not the whole page (ADR 0006). The
      // stamped flat index pins which section (and which copy) was tapped when
      // the same identity appears more than once; it is only a tie-breaker — a
      // stale index falls back to the first identity match. An id that no
      // longer appears on the page at all aborts the expansion — the caller
      // falls back to the stored single track; silently queueing the changed
      // list would resume the wrong station.
      val tappedIndex = BrowserPathHelper.extractIndex(contextualPath)
      val scoped = SectionScope.scoped(sections, trackId, tappedIndex) ?: return null
      val sectionTracks = scoped.section.children.toList()
      val tappedOffset = scoped.tappedOffset

      // Filter to only playable tracks (tracks with src). A disabled track is
      // unavailable — queue expansion excludes it, so auto-advance never meets
      // one.
      val playableTracks =
        sectionTracks.filter { track -> track.src != null && track.disabled != true }

      if (playableTracks.isEmpty()) {
        Timber.w("Parent has no playable tracks, cannot expand queue")
        return null
      }

      // Find the index of the selected track in the playable tracks array:
      // the pinned copy when the stamp survived, else the first identity match
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

      // Check singleTrack setting - if true, return only the selected track
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
   * Navigate to a path and return browser content.
   *
   * Uses a navigation ID to prevent race conditions when multiple navigations overlap. Only the
   * most recent navigation's result is applied.
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
   * Refresh the current path's content without changing navigation state. Used for background
   * refreshes (e.g., when content changes via notifyContentChanged). Bypasses content cache to
   * fetch fresh data. Errors are silently ignored.
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
    val (cachedQuery, cachedResults) = lastSearch ?: return null
    if (query != cachedQuery) return null
    return cachedResults.map { hydrateFavorite(it) }.toTypedArray()
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
        reference = MediaReference.UNKNOWN,
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
    // Voice search never matches a disabled track (Track.disabled) — filtered
    // before the first result is picked, so an unavailable one can't capture
    // the play.
    val tracks = searchResults.flattenedChildren?.filter { it.disabled != true }?.toTypedArray()

    if (tracks.isNullOrEmpty()) {
      return null
    }

    val firstResult = tracks[0]

    // Check if result is browsable-only (container/route) vs playable
    // If it's browsable but also playable (has src or playable=true), treat it as playable
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
        reference = MediaReference.UNKNOWN,
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
      // Execute search. Drop results without a stable identifier (path or src): search results come
      // from server/JS data that doesn't pass through validateTrack like browse children do, and
      // downstream conversion (TrackFactory.toMedia3) and browsable-result resolution require one.
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

      // Search results are a flat list; as a resolved page they are one
      // untitled section (ADR 0010).
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
        )

      // Cache search results for getCachedSearchResults()
      lastSearch = params.query to searchResults

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
          id = null,
          path = searchPath,
          title = "Search: ${params.query}",
          sections = arrayOf(untitledSection(emptyArray())),
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
   * Set callback invoked whenever both [artworkResolutions] and the browse-artwork registry are
   * cleared (config swap or content invalidation). The caller should clear any parallel
   * [com.audiobrowser.browser.BrowseArtworkRegistry] instance it owns.
   */
  fun setOnArtworkRegistriesCleared(callback: () -> Unit) {
    onArtworkRegistriesCleared = callback
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
      Timber.d("[TABS] Tab[$index] '${tab.title}': path=${tab.path}")
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
      // BrowserSourceCallback may return a BrowseResult synchronously or via a
      // Promise. Nitro flattens (ResolvedTrack | BrowseError) | Promise<BrowseResult>
      // into a 3-arm variant: sync track, sync error, or a Promise resolving to a
      // BrowseResult (which is itself a ResolvedTrack | BrowseError variant).
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

    // Tabs are a flat list, not a page — a sectioned tabs source flattens.
    return resolvedTrack.flattenedChildren?.toTypedArray() ?: emptyArray()
  }

  /**
   * Resolves a single request/browse layer. When a resolver thunk is present it is invoked and its
   * result awaited. The resolver is Promise-only (the TS layer normalizes a sync-or-async thunk via
   * Promise.resolve), so its native shape is `Promise<Promise<TransformableRequestConfig>>` — a
   * double await. When there is no resolver the static layer config is returned as-is.
   */
  private suspend fun resolveLayer(
    staticConfig: TransformableRequestConfig?,
    resolver: Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____?,
  ): TransformableRequestConfig? {
    if (resolver == null) return staticConfig
    // Promise-only resolver (the TS layer wraps a sync-or-async thunk in
    // Promise.resolve) → double await: the bridge promise, then the JS promise.
    return resolver.invoke().await().await()
  }

  /**
   * Ensures the request/browse resolver layers are resolved for the current [layerGeneration],
   * caching the result. Re-resolves when the generation changes (config set or content
   * invalidation). Uses a simple generation guard with no in-flight cache: a benign idempotent
   * double-resolve under concurrent first-requests is acceptable, and on a thrown resolver nothing
   * is cached so the next request retries naturally.
   */
  internal suspend fun ensureLayersResolved() {
    if (resolvedLayerGeneration == layerGeneration) return
    val generation = layerGeneration
    val req = resolveLayer(config.request, config.requestResolver)
    val brw = resolveLayer(config.browse, config.browseResolver)
    // A newer generation started while we were awaiting — drop this stale result.
    if (generation != layerGeneration) return
    resolvedRequestLayer = req
    resolvedBrowseLayer = brw
    resolvedLayerGeneration = generation
  }

  /**
   * Ensures the request layer is resolved for the current generation and returns it (the resolver
   * result when a [BrowserConfig.requestResolver] is configured, else the static
   * [BrowserConfig.request]).
   *
   * Consumers outside the browse path (media URL building, artwork) must obtain the request layer
   * through this accessor rather than reading the static `config.request`, so a resolver-only
   * consumer still gets a baseUrl/headers/transform for media, artwork, and now-playing artwork.
   */
  internal suspend fun resolvedRequestConfig(): TransformableRequestConfig? {
    ensureLayersResolved()
    return resolvedRequestLayer
  }

  /**
   * Builds the HTTP request for an API-backed path by layering request (shared) → kind
   * (browse/search) → route configs. Each layer's transform receives the previous layer's output; a
   * layer with no transform merges its static fields.
   *
   * `initialQuery` seeds query params onto the BASE the kind layer receives (e.g. search q/mode/…):
   * a layer with a transform "wins completely" and is handed only the base, so params placed on a
   * layer's own static query would be dropped before the transform runs. The same goes for `path`:
   * it is carried from the base through every layer (only a transform may change it), so a kind
   * whose config supplies the path (search) must seed it via the `path` parameter. Mirrors iOS
   * `buildApiRequest`.
   *
   * @throws ContentNotFoundException when no layer supplies a baseUrl — there is nothing to fetch,
   *   so the path is genuinely "not found" rather than a network error (mirrors iOS's `guard let
   *   baseUrl`).
   */
  internal suspend fun buildApiRequest(
    kindConfig: TransformableRequestConfig?,
    routeConfig: TransformableRequestConfig?,
    path: String?,
    params: Map<String, String>,
    initialQuery: Map<String, String>? = null,
  ): HttpClient.HttpRequest {
    // Resolve the request/browse resolver thunks once per content generation (cached).
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

  /**
   * Execute an API request for browser content. Request building (layering + transforms + baseUrl
   * guard) lives in [buildApiRequest]; this adds the browse-specific response shape (a
   * ResolvedTrack page object).
   */
  private suspend fun executeApiRequest(
    apiConfig: TransformableRequestConfig?,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    return withContext(Dispatchers.IO) {
      // request (shared) → browse (kind) → route. apiConfig is null for the
      // implicit default (an unmatched browse path → fetch via request + browse + path).
      ensureLayersResolved()
      val httpRequest = buildApiRequest(resolvedBrowseLayer, apiConfig, path, routeParams)
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

        // request (shared) → search (kind); no browse layer and no route — search
        // is its own kind. The search params seed the base (see buildApiRequest
        // docs), and so does the search config's path: a layer's static path
        // never applies (the path is carried from the base), so the caller
        // seeds it — mirrors the web stub's fetchSearchResults.
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
              // The search endpoint returns a bare Track array (unlike browse,
              // which returns a page object). iOS parses it the same way.
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
class ContentNotFoundException(val path: String) : Exception("Content not found")

/** Exception thrown when an HTTP request fails with a non-2xx status code. */
class HttpStatusException(val statusCode: Int, message: String) : Exception(message)

/** Exception thrown when a network request fails (connection error, timeout, etc). */
class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Exception thrown when a browse callback returns an error message. */
class CallbackException(message: String) : Exception(message)

/**
 * Configuration object that holds all browser settings. Uses flattened structure matching
 * NativeBrowserConfiguration from JS.
 */
data class BrowserConfig(
  val request: TransformableRequestConfig? = null,
  // Resolver thunk for the shared request layer. When set, it is resolved once per content
  // generation (re-resolved after invalidateAllContent), cached, and merged per request — instead
  // of carrying a static `request`. `request` and `requestResolver` are mutually exclusive in
  // practice (the consumer sets one or the other), but both are merged if present.
  val requestResolver:
    Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____? =
    null,
  val browse: TransformableRequestConfig? = null,
  // Resolver thunk for the browse layer. See `requestResolver`.
  val browseResolver:
    Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____? =
    null,
  val media: MediaRequestConfig? = null,
  val artwork: ArtworkRequestConfig? = null,
  // Now-playing-only artwork configuration (lock screen / notification / Android Auto now-playing).
  // A distinct kind from `artwork`; the now-playing path falls back to `artwork` when this is null.
  val nowPlayingArtwork: ArtworkRequestConfig? = null,
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
