package com.audiobrowser.util

import com.audiobrowser.browser.BrowseArtworkRegistry
import kotlinx.coroutines.CoroutineScope

/** Dependencies the exported [ArtworkContentProvider] needs, published by the media Service. */
data class ArtworkProviderDeps(
  val loader: CoilArtworkLoader,
  val registry: BrowseArtworkRegistry,
  val scope: CoroutineScope,
  val artworkSizeHint: () -> Int? = { null },
)

/**
 * Process-wide handoff between the media Service (which builds the deps) and the ContentProvider
 * (which the OS may instantiate before the Service exists). `@Volatile` publishes the reference
 * safely across the binder/main threads.
 */
object CoilArtworkLoaderHolder {
  @Volatile private var deps: ArtworkProviderDeps? = null

  fun set(deps: ArtworkProviderDeps) {
    this.deps = deps
  }

  fun get(): ArtworkProviderDeps? = deps

  /**
   * Clears only if [deps] is still the current one — prevents a stale Service from blanking a
   * newer.
   */
  @Synchronized
  fun clearIf(deps: ArtworkProviderDeps) {
    if (this.deps === deps) this.deps = null
  }
}
