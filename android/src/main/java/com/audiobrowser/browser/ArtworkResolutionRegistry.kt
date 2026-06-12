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
}
