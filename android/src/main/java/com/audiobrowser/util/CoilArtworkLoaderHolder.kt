package com.audiobrowser.util

import com.audiobrowser.browser.BrowseArtworkRegistry
import kotlinx.coroutines.CoroutineScope

/** What [ArtworkContentProvider] needs to serve artwork; the media Service builds it. */
data class ArtworkProviderDeps(
  val loader: CoilArtworkLoader,
  val registry: BrowseArtworkRegistry,
  val scope: CoroutineScope,
  val artworkSizeHint: () -> Int? = { null },
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
