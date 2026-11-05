package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.Track

/**
 * Maps a produced artwork URI back to the Track and artwork config it came from, so the
 * display-time bitmap loader, which gets only the URI from Media3, re-resolves Track-first with the
 * real size instead of re-running the transform on an already-transformed URL (safe only for
 * idempotent transforms). Bounded LRU; thread-safe (written from browse coroutines and the
 * now-playing scope, read from the bitmap loader's IO scope).
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
   * Drops all entries. Call whenever the browser config is replaced or content is invalidated:
   * entries hold Tracks and configs whose transform/resolve fields are Nitro handles to JS
   * closures, and keeping them both retains dead closures and resolves artwork through stale
   * callbacks.
   */
  @Synchronized fun clear() = entries.clear()
}
