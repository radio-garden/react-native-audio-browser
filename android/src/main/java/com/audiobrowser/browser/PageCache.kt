package com.audiobrowser.browser

import android.util.LruCache
import com.audiobrowser.http.Fetched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Browse pages by path, each kept for the lifetime its fetch states (at least [LIFETIME_FLOOR_MS]),
 * or with none until evicted or removed. An expired page is served at once while one refetch per
 * path runs behind it, one path at a time and only while [canRefetch]. [onExpired] gets a page's
 * path once its lifetime passes on [now], and again a lifetime after each failed refetch;
 * [onRefreshed] gets it once a refetch stored a page. Every fetch and timer runs on [scope]. Safe
 * from any thread.
 *
 * @param keepsStale whether a failed refetch keeps the stale page; any other failure drops it
 */
class PageCache<V : Any>(
  maxSize: Int,
  private val scope: CoroutineScope,
  private val now: () -> Long,
  private val canRefetch: () -> Boolean,
  private val keepsStale: (Exception) -> Boolean,
  private val onExpired: (String) -> Unit,
  private val onRefreshed: (String) -> Unit,
) {
  private data class Entry<V>(
    val value: V,
    val lifetimeMs: Long?,
    val expiresAt: Long?,
    val timer: Job?,
    /** After a failed refetch, when the next may start; on [now]'s clock. */
    val retryAt: Long? = null,
  )

  private class Load<V>(val refetch: Boolean) {
    val result = CompletableDeferred<V>()
  }

  private val entries =
    object : LruCache<String, Entry<V>>(maxSize) {
      override fun entryRemoved(evicted: Boolean, key: String, old: Entry<V>, new: Entry<V>?) {
        if (new?.timer !== old.timer) old.timer?.cancel()
      }
    }
  private val lock = Any()
  /** The one fetch per path. Removing a path forgets its load, which then stores nothing. */
  private val loads = HashMap<String, Load<V>>()
  private val refetches = Semaphore(1)

  /**
   * The cached page at [path], refetched behind it when it has expired; else what [fetch] gives,
   * shared with every caller asking meanwhile, and cached for the lifetime it states.
   *
   * @throws CancellationException once [scope] is cancelled
   */
  suspend fun value(path: String, fetch: suspend () -> Fetched<V>): V {
    val load =
      synchronized(lock) {
        val cached = entries.get(path)
        if (cached != null) {
          if (isDue(cached) && canRefetch()) load(path, fetch, refetch = true)
          return cached.value
        }
        load(path, fetch, refetch = false)
      }
    return load.result.await()
  }

  /**
   * Refetches [path]'s expired page, or joins the refetch in flight, and returns once it settles.
   * Does nothing for a fresh or uncached page, or while it may not refetch.
   */
  suspend fun revalidate(path: String, fetch: suspend () -> Fetched<V>) {
    val load =
      synchronized(lock) {
        loads[path]
          ?: entries
            .get(path)
            ?.takeIf { isDue(it) && canRefetch() }
            ?.let { load(path, fetch, refetch = true) }
      }
    try {
      load?.result?.await()
    } catch (e: CancellationException) {
      if (!scope.isActive) return
      throw e
    } catch (_: Exception) {}
  }

  fun remove(path: String) {
    synchronized(lock) {
      loads.remove(path)
      entries.remove(path)
    }
  }

  fun clear() {
    synchronized(lock) {
      loads.clear()
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

  private fun isDue(entry: Entry<V>) = !isFresh(entry) && (entry.retryAt ?: Long.MIN_VALUE) <= now()

  /**
   * Under [lock]. The path's load, started on [scope] unless one runs; once [scope] is cancelled it
   * fails at once.
   */
  private fun load(path: String, fetch: suspend () -> Fetched<V>, refetch: Boolean): Load<V> {
    loads[path]?.let {
      return it
    }
    val load = Load<V>(refetch).also { loads[path] = it }
    scope
      .launch {
        val result = runCatching {
          if (refetch) {
            refetches.withPermit {
              if (synchronized(lock) { loads[path] !== load }) throw Forgotten()
              fetch()
            }
          } else {
            fetch()
          }
        }
        settle(path, load, result)
      }
      .invokeOnCompletion { cause ->
        if (cause == null) return@invokeOnCompletion
        synchronized(lock) { if (loads[path] === load) loads.remove(path) }
        load.result.completeExceptionally(cause)
      }
    return load
  }

  /**
   * Stores what [load] fetched, or keeps or drops the cached page by its failure, unless a removal
   * forgot [load] meanwhile; then completes it for whoever joined it.
   */
  private fun settle(path: String, load: Load<V>, result: Result<Fetched<V>>) {
    val refreshed =
      synchronized(lock) {
        if (loads[path] !== load) return@synchronized false
        loads.remove(path)
        result.fold(
          onSuccess = { fetched ->
            val lifetimeMs = fetched.lifetimeMs?.let { maxOf(it, LIFETIME_FLOOR_MS) }
            val remainingMs =
              fetched.lifetimeMs?.let { maxOf(it - fetched.ageMs, LIFETIME_FLOOR_MS) }
            store(path, fetched.value, lifetimeMs, remainingMs?.let { now() + it }, remainingMs)
            load.refetch
          },
          onFailure = { e ->
            val cached = entries.get(path)
            if (cached != null && e is Exception) {
              if (e is CancellationException || keepsStale(e)) {
                val retryInMs = cached.lifetimeMs
                store(path, cached.value, cached.lifetimeMs, cached.expiresAt, retryInMs, retryInMs)
              } else {
                entries.remove(path)
              }
            }
            false
          },
        )
      }
    result.fold(
      onSuccess = { load.result.complete(it.value) },
      onFailure = { load.result.completeExceptionally(it) },
    )
    if (refreshed) onRefreshed(path)
  }

  /**
   * Under [lock]. Stores the page with a timer that pushes it [pushInMs] from now, if any, and no
   * refetch before [retryInMs] from now.
   */
  private fun store(
    path: String,
    value: V,
    lifetimeMs: Long?,
    expiresAt: Long?,
    pushInMs: Long?,
    retryInMs: Long? = null,
  ) {
    val due = pushInMs?.let { now() + it }
    val timer =
      due?.let {
        scope.launch {
          // A delay stops in deep sleep and [now] does not, so a long one is cut into steps.
          while (true) delay(minOf(due - now(), MAX_STEP_MS).takeIf { it > 0 } ?: break)
          val timer = coroutineContext[Job]
          if (synchronized(lock) { entries.snapshot()[path]?.timer === timer }) onExpired(path)
        }
      }
    entries.put(path, Entry(value, lifetimeMs, expiresAt, timer, retryInMs?.let { now() + it }))
  }

  /** A queued refetch whose path was removed or cleared before its turn. */
  private class Forgotten : Exception()

  companion object {
    /** The shortest a page is kept, however stale its response arrived: no refetch loops. */
    const val LIFETIME_FLOOR_MS = 30_000L
    private const val MAX_STEP_MS = 60_000L
  }
}
