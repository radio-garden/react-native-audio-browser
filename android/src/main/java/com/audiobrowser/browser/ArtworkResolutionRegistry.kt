package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.Track

/**
 * Maps a produced artwork URI back to the track and artwork config that made it, so the
 * display-time bitmap loader, which gets only the URI from Media3, re-resolves with the real size
 * instead of transforming an already-transformed URL. Thread-safe.
 */
class ArtworkResolutionRegistry(private val maxEntries: Int = 256) {

  /** A null [perRouteConfig] resolves through the browser's current `config.artwork`. */
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
   * Call whenever the browser config is replaced or content is invalidated: entries hold Nitro
   * handles to JS closures, which would otherwise be retained and resolve through stale callbacks.
   */
  @Synchronized fun clear() = entries.clear()
}
