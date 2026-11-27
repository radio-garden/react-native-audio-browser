package com.audiobrowser.util

import com.audiobrowser.browser.BrowseArtworkRegistry
import com.margelo.nitro.audiobrowser.ImageSource
import kotlinx.coroutines.CoroutineScope

/** What [ArtworkContentProvider] needs to serve artwork; the media Service builds it. */
data class ArtworkProviderDeps(
  val loader: CoilArtworkLoader,
  val registry: BrowseArtworkRegistry,
  val scope: CoroutineScope,
  val artworkSizeHint: () -> Int? = { null },
  /**
   * The browse artwork registered as `url`, re-resolved for the car's size hint; null when none is
   * registered, which fetches the URL as registered.
   */
  val resolveBrowseArtwork: suspend (url: String, sizeHintPixels: Int) -> ImageSource?,
)

/**
 * Hands the Service's [ArtworkProviderDeps] to [ArtworkContentProvider], which the OS instantiates
 * itself and may create before the Service exists: [get] is null until then. Read on binder
 * threads, written on the main thread.
 */
object CoilArtworkLoaderHolder {
  @Volatile private var deps: ArtworkProviderDeps? = null

  fun set(deps: ArtworkProviderDeps) {
    this.deps = deps
  }

  fun get(): ArtworkProviderDeps? = deps

  /** Clears only if [deps] is still current, so a destroyed Service never clears a newer one's. */
  @Synchronized
  fun clearIf(deps: ArtworkProviderDeps) {
    if (this.deps === deps) this.deps = null
  }
}
