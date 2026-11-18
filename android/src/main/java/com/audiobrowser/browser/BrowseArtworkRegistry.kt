package com.audiobrowser.browser

/**
 * Token → resolved artwork, the only data [com.audiobrowser.util.ArtworkContentProvider] serves.
 * Thread-safe.
 */
class BrowseArtworkRegistry(
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
    object : LinkedHashMap<String, ResolvedArtwork>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, ResolvedArtwork>
      ): Boolean = size > maxEntries
    }
}

data class ResolvedArtwork(
  val finalUrl: String,
  val headers: Map<String, String>?,
  val isSvg: Boolean,
)
