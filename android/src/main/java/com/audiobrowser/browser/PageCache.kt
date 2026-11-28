package com.audiobrowser.browser

import android.os.SystemClock
import android.util.LruCache
import com.audiobrowser.http.Fetched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browse pages by path, each kept for the lifetime its fetch states, or with none until evicted or
 * removed. [onExpired] gets a page's path once its lifetime passes, on [scope]. Safe from any
 * thread.
 */
class PageCache<V : Any>(
  maxSize: Int,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val now: () -> Long = SystemClock::elapsedRealtime,
  private val onExpired: (String) -> Unit,
) {
  private class Entry<V>(
    val value: V,
    val lifetimeMs: Long?,
    val expiresAt: Long?,
    val stamp: Int,
    val failures: Int,
    val timer: Job?,
  )

  private val entries =
    object : LruCache<String, Entry<V>>(maxSize) {
      override fun entryRemoved(evicted: Boolean, key: String, old: Entry<V>, new: Entry<V>?) {
        if (new?.timer !== old.timer) old.timer?.cancel()
      }
    }
  private val lock = Any()
  private var stamps = 0
  /**
   * Moves on [clear], so a fetch that outlives one caches nothing; [pathGenerations] does the same
   * for one path on [remove].
   */
  @Volatile
  var generation = 0
    private set

  private val pathGenerations = HashMap<String, Int>()

  /**
   * The cached page at [path] while it is fresh and [useCache], else what [fetch] gives, cached for
   * the lifetime it states. When [fetch] fails for an expired page, the page is kept, expired, and
   * pushed again a lifetime later, up to [MAX_RETRIES] times; with [useCache] it is returned,
   * stale, in place of the error. A failed refetch of a fresh page changes nothing.
   */
  suspend fun value(path: String, useCache: Boolean, fetch: suspend () -> Fetched<V>): V {
    val (cached, generation) = synchronized(lock) { entries.get(path) to generationOf(path) }
    if (useCache && cached != null && isFresh(cached)) return cached.value
    val fetched =
      try {
        fetch()
      } catch (e: Exception) {
        if (cached == null || isFresh(cached) || e is CancellationException) throw e
        val current =
          synchronized(lock) {
            // A page removed or cleared meanwhile is gone, stale fallback included.
            if (generation != generationOf(path)) throw e
            // A newer page stored meanwhile wins over the stale one this fetch replaced; an update
            // keeps the stamp, so its value is the one kept.
            entries.snapshot()[path]?.also {
              if (it.stamp == cached.stamp) {
                store(path, it.value, it.lifetimeMs, it.expiresAt, it.failures + 1)
              }
            }
          }
        if (useCache) return current?.value ?: cached.value
        throw e
      }
    synchronized(lock) {
      if (generation == generationOf(path)) {
        val expiresAt = fetched.lifetimeMs?.let { now() + it }
        store(path, fetched.value, fetched.lifetimeMs, expiresAt, failures = 0)
      }
    }
    return fetched.value
  }

  fun remove(path: String) {
    synchronized(lock) {
      pathGenerations[path] = (pathGenerations[path] ?: 0) + 1
      entries.remove(path)
    }
  }

  fun clear() {
    synchronized(lock) {
      generation += 1
      pathGenerations.clear()
      entries.evictAll()
    }
  }

  /** Replaces each page [transform] returns a new value for, keeping its expiry. */
  fun update(transform: (V) -> V?) {
    synchronized(lock) {
      entries.snapshot().forEach { (path, entry) ->
        transform(entry.value)?.let {
          entries.put(
            path,
            Entry(it, entry.lifetimeMs, entry.expiresAt, entry.stamp, entry.failures, entry.timer),
          )
        }
      }
    }
  }

  private fun isFresh(entry: Entry<V>) = (entry.expiresAt ?: Long.MAX_VALUE) > now()

  private fun generationOf(path: String) = generation to pathGenerations[path]

  /**
   * Under [lock]. Stores the page under a new stamp, with a timer that pushes it a lifetime from
   * now unless it is past [MAX_RETRIES] failures.
   */
  private fun store(path: String, value: V, lifetimeMs: Long?, expiresAt: Long?, failures: Int) {
    val timer =
      lifetimeMs
        ?.takeIf { failures <= MAX_RETRIES }
        ?.let { delayMs ->
          scope.launch {
            delay(delayMs)
            onExpired(path)
          }
        }
    entries.put(path, Entry(value, lifetimeMs, expiresAt, ++stamps, failures, timer))
  }

  companion object {
    /** Failed refreshes of an expired page retried a lifetime apart before it waits for a visit. */
    const val MAX_RETRIES = 3
  }
}
