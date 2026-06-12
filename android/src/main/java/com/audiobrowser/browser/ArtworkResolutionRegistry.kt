package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.Track

/**
 * Remembers how an artwork URI was produced (which Track, which artwork-config
 * kind) so the display-time bitmap loader can re-resolve it Track-first with a
 * real size hint — instead of re-running the artwork Transform on an
 * already-transformed URL, which is only safe for idempotent transforms.
 * Bounded LRU; thread-safe (registered from browse coroutines and the
 * now-playing scope, read from the bitmap loader's IO scope).
 */
class ArtworkResolutionRegistry(private val maxEntries: Int = 256) {

  data class Entry(val track: Track, val perRouteConfig: ArtworkRequestConfig?)

  private val entries =
    object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
        size > maxEntries
    }

  @Synchronized
  fun register(uri: String, track: Track, perRouteConfig: ArtworkRequestConfig?) {
    entries[uri] = Entry(track, perRouteConfig)
  }

  @Synchronized fun lookup(uri: String): Entry? = entries[uri]

  /**
   * Drops all entries. Must run whenever the browser config is replaced or content
   * is invalidated: entries pin Tracks and ArtworkRequestConfigs whose
   * transform/resolve fields are Nitro handles to JS closures — keeping them past
   * their config's lifetime both retains dead closures and resolves display-time
   * artwork through stale callbacks.
   */
  @Synchronized fun clear() = entries.clear()
}
