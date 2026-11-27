package com.audiobrowser.browser

/**
 * Token → resolved artwork, the only data the [com.audiobrowser.util.ArtworkContentProvider]
 * serves. Thread-safe: written from browse coroutines, read from the provider's IO scope.
 */
class BrowseArtworkRegistry(
  private val maxEntries: Int = MAX_ENTRIES,
  private val maxPinnedEntries: Int = 2048,
  // A `data:` URL is its own payload, so entries are also bounded by the characters they hold.
  private val maxUrlChars: Int = 4_000_000,
) {

  private val entries = Lru(maxEntries, maxUrlChars)
  private val pinned = Lru(maxPinnedEntries, maxUrlChars)

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

  companion object {
    const val MAX_ENTRIES = 2048
  }

  /** Least recently used first out, past [maxEntries] or [maxUrlChars]; the newest always stays. */
  private class Lru(private val maxEntries: Int, private val maxUrlChars: Int) {
    // Access order, so a lookup keeps an entry from eviction.
    private val map = LinkedHashMap<String, ResolvedArtwork>(16, 0.75f, true)
    private var urlChars = 0L

    operator fun get(token: String): ResolvedArtwork? = map[token]

    operator fun set(token: String, artwork: ResolvedArtwork) {
      map.put(token, artwork)?.let { urlChars -= it.finalUrl.length }
      urlChars += artwork.finalUrl.length
      val eldest = map.entries.iterator()
      while (map.size > 1 && (map.size > maxEntries || urlChars > maxUrlChars)) {
        urlChars -= eldest.next().value.finalUrl.length
        eldest.remove()
      }
    }

    fun clear() {
      map.clear()
      urlChars = 0
    }
  }
}

/** Everything the provider needs to fetch one artwork. */
data class ResolvedArtwork(
  val finalUrl: String,
  val headers: Map<String, String>?,
  val isSvg: Boolean,
)
