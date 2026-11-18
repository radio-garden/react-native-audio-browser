package com.audiobrowser.browser

/**
 * Token → resolved artwork, the only data the [com.audiobrowser.util.ArtworkContentProvider]
 * serves. Thread-safe: written from browse coroutines, read from the provider's IO scope.
 */
class BrowseArtworkRegistry(
  // Entries hold plain data, no JS closures (unlike [ArtworkResolutionRegistry]), so they are cheap
  // to keep many of.
  private val maxEntries: Int = 2048,
  private val maxPinnedEntries: Int = 2048,
) {

  private val entries = lruMap(maxEntries)
  private val pinned = lruMap(maxPinnedEntries)

  @Synchronized
  fun register(token: String, artwork: ResolvedArtwork) {
    entries[token] = artwork
  }

  /**
   * Registers artwork that [clear] keeps: browse items are rebuilt after a clear, but media items
   * already in the queue keep their URIs.
   */
  @Synchronized
  fun pin(token: String, artwork: ResolvedArtwork) {
    pinned[token] = artwork
  }

  @Synchronized fun lookup(token: String): ResolvedArtwork? = entries[token] ?: pinned[token]

  /** Drops the [register]ed entries; [pin]ned ones stay. */
  @Synchronized fun clear() = entries.clear()

  private fun lruMap(maxEntries: Int) =
    // Access order, so a lookup keeps an entry from eviction.
    object : LinkedHashMap<String, ResolvedArtwork>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, ResolvedArtwork>
      ): Boolean = size > maxEntries
    }
}

/** Everything the provider needs to fetch one artwork. */
data class ResolvedArtwork(
  val finalUrl: String,
  val headers: Map<String, String>?,
  val isSvg: Boolean,
)
