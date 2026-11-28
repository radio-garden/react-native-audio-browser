package com.audiobrowser.browser

import android.util.LruCache
import com.audiobrowser.http.Fetched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browse pages by path, each kept for the lifetime its fetch states, or with none until evicted or
 * removed. [onExpired] gets a page's path once its lifetime passes on [now], on [scope]. Safe from
 * any thread.
 */
class PageCache<V : Any>(
  maxSize: Int,
  private val scope: CoroutineScope,
  private val now: () -> Long,
  private val onExpired: (String) -> Unit,
) {
  private data class Entry<V>(
    val value: V,
    val lifetimeMs: Long?,
    val expiresAt: Long?,
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
  /**
   * A path's version moves on every store, remove and clear, so a fetch that outlives one stores
   * nothing.
   */
  private val versions = HashMap<String, Int>()
  private var clearedVersion = 0
  private var lastVersion = 0

  /**
   * The cached page at [path] while it is fresh, else what [fetch] gives, cached for the lifetime
   * it states and pushed when that passes (a page whose age used it up is cached expired, with no
   * push). When [fetch] fails, an expired page is returned and kept, stale, and pushed again a
   * lifetime later, up to [MAX_RETRIES] times; a page stored meanwhile is returned instead.
   */
  suspend fun value(path: String, fetch: suspend () -> Fetched<V>): V {
    val (cached, version) = synchronized(lock) { entries.get(path) to versionOf(path) }
    if (cached != null && isFresh(cached)) return cached.value
    val fetched =
      try {
        fetch()
      } catch (e: Exception) {
        if (cached == null || e is CancellationException) throw e
        synchronized(lock) {
          val current = entries.snapshot()[path]
          // Null when the page was removed or cleared meanwhile: it is gone, stale fallback
          // included.
          if (version != versionOf(path)) return current?.value ?: throw e
          // An update keeps the version, so its value is the one kept.
          val kept = current ?: cached
          store(
            path,
            kept.value,
            kept.lifetimeMs,
            kept.expiresAt,
            kept.failures + 1,
            kept.lifetimeMs,
          )
          return kept.value
        }
      }
    synchronized(lock) {
      if (version == versionOf(path)) {
        val expiresAt = fetched.lifetimeMs?.let { now() + it - fetched.ageMs }
        store(path, fetched.value, fetched.lifetimeMs, expiresAt, 0, expiresAt?.let { it - now() })
      }
    }
    return fetched.value
  }

  fun remove(path: String) {
    synchronized(lock) {
      bump(path)
      entries.remove(path)
    }
  }

  fun clear() {
    synchronized(lock) {
      clearedVersion = ++lastVersion
      versions.clear()
      entries.evictAll()
    }
  }

  /** Replaces each page [transform] returns a new value for, keeping its expiry. */
  fun update(transform: (V) -> V?) {
    synchronized(lock) {
      entries.snapshot().forEach { (path, entry) ->
        transform(entry.value)?.let { entries.put(path, entry.copy(value = it)) }
      }
    }
  }

  private fun isFresh(entry: Entry<V>) = (entry.expiresAt ?: Long.MAX_VALUE) > now()

  private fun versionOf(path: String) = versions[path] ?: clearedVersion

  private fun bump(path: String): Int = (++lastVersion).also { versions[path] = it }

  /**
   * Under [lock]. Stores the page with a timer that pushes it [pushInMs] from now, unless that is
   * not positive or the page is past [MAX_RETRIES] failures.
   */
  private fun store(
    path: String,
    value: V,
    lifetimeMs: Long?,
    expiresAt: Long?,
    failures: Int,
    pushInMs: Long?,
  ) {
    val version = bump(path)
    val timer =
      pushInMs
        ?.takeIf { it > 0 && failures <= MAX_RETRIES }
        ?.let { delayMs ->
          val due = now() + delayMs
          scope.launch {
            // A delay stops in deep sleep and [now] does not, so a long one is cut into steps.
            while (true) delay(minOf(due - now(), MAX_STEP_MS).takeIf { it > 0 } ?: break)
            if (synchronized(lock) { versionOf(path) == version }) onExpired(path)
          }
        }
    entries.put(path, Entry(value, lifetimeMs, expiresAt, failures, timer))
  }

  companion object {
    /** Failed refreshes of an expired page retried a lifetime apart before it waits for a visit. */
    const val MAX_RETRIES = 3
    private const val MAX_STEP_MS = 60_000L
  }
}
