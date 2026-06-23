package com.audiobrowser.browser

/**
 * Token → resolved browse artwork, the [com.audiobrowser.util.ArtworkContentProvider]'s only data
 * source. Stores plain resolved data (no Track / Nitro handles), so unlike [ArtworkResolutionRegistry]
 * it pins no JS closures and can be sized generously. Cleared when the browser config is replaced or
 * content is invalidated. Thread-safe: written from browse coroutines, read from the provider's IO scope.
 */
class BrowseArtworkRegistry(private val maxEntries: Int = 2048) {

  private val entries =
    object : LinkedHashMap<String, ResolvedArtwork>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedArtwork>): Boolean =
        size > maxEntries
    }

  @Synchronized
  fun register(token: String, artwork: ResolvedArtwork) {
    entries[token] = artwork
  }

  @Synchronized fun lookup(token: String): ResolvedArtwork? = entries[token]

  @Synchronized fun clear() = entries.clear()
}

/** Everything the provider needs to fetch one artwork, resolved at browse-build time. */
data class ResolvedArtwork(
  val finalUrl: String,
  val headers: Map<String, String>?,
  val isSvg: Boolean,
)
