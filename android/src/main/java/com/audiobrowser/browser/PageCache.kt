package com.audiobrowser.browser

import android.util.LruCache
import com.audiobrowser.http.Fetched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Browse pages by path, each kept for the lifetime its fetch states, or with none until evicted or
 * removed. An expired page is served at once while one refetch per path runs behind it, one path at
 * a time and only while [canRefetch]. [onExpired] gets a page's path once its lifetime passes on
 * [now], and [onRefreshed] once a refetch replaced it. Refetches and timers run on [scope]. Safe
 * from any thread.
 *
 * @param isTransient whether a failed refetch keeps the stale page and retries; any other failure
 *   drops it
 */
class PageCache<V : Any>(
  maxSize: Int,
  private val scope: CoroutineScope,
  private val now: () -> Long,
  private val canRefetch: () -> Boolean,
  private val isTransient: (Exception) -> Boolean,
  private val onExpired: (String) -> Unit,
  private val onRefreshed: (String) -> Unit,
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
  /** The one fetch per path. Removing a path forgets its load, which then stores nothing. */
  private val loads = HashMap<String, CompletableDeferred<V>>()
  private val refetches = Semaphore(1)

  /**
   * The cached page at [path], refetched behind it when it has expired; else what [fetch] gives,
   * shared with every caller asking meanwhile, and cached for the lifetime it states.
   */
  suspend fun value(path: String, fetch: suspend () -> Fetched<V>): V {
    while (true) {
      var mine = false
      val load =
        synchronized(lock) {
          val cached = entries.get(path)
          if (cached != null) {
            if (!isFresh(cached)) refetch(path, fetch)
            return cached.value
          }
          loads[path]
            ?: CompletableDeferred<V>().also {
              loads[path] = it
              mine = true
            }
        }
      if (mine) return fetchInto(path, load, fetch)
      try {
        return load.await()
      } catch (_: Abandoned) {}
    }
  }

  /**
   * Refetches [path]'s expired page, or joins the refetch in flight, and returns once it settles.
   * Does nothing for a fresh or uncached page, or while it may not refetch.
   */
  suspend fun revalidate(path: String, fetch: suspend () -> Fetched<V>) {
    val load =
      synchronized(lock) {
        loads[path]
          ?: entries.snapshot()[path]?.takeIf { !isFresh(it) }?.let { refetch(path, fetch) }
      }
    try {
      load?.await()
    } catch (e: CancellationException) {
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

  /** Under [lock]. Starts a refetch on [scope] unless one runs or it may not refetch. */
  private fun refetch(path: String, fetch: suspend () -> Fetched<V>): CompletableDeferred<V>? {
    loads[path]?.let {
      return it
    }
    if (!canRefetch()) return null
    val load = CompletableDeferred<V>().also { loads[path] = it }
    scope.launch { runCatching { refetches.withPermit { fetchInto(path, load, fetch) } } }
    return load
  }

  /**
   * Runs [fetch] for [load], the path's load, stores what it gives or settles the cached page by
   * its failure, and completes [load] for whoever joined it. A removal meanwhile stores nothing.
   */
  private suspend fun fetchInto(
    path: String,
    load: CompletableDeferred<V>,
    fetch: suspend () -> Fetched<V>,
  ): V {
    val result =
      try {
        Result.success(fetch())
      } catch (e: CancellationException) {
        synchronized(lock) { if (loads[path] === load) loads.remove(path) }
        load.completeExceptionally(Abandoned())
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }
    val replaced =
      synchronized(lock) {
        if (loads[path] !== load) return@synchronized false
        loads.remove(path)
        val cached = entries.snapshot()[path]
        result.fold(
          onSuccess = { fetched ->
            val expiresAt = fetched.lifetimeMs?.let { now() + it - fetched.ageMs }
            store(
              path,
              fetched.value,
              fetched.lifetimeMs,
              expiresAt,
              0,
              expiresAt?.let { it - now() },
            )
            cached != null
          },
          onFailure = { e ->
            if (cached != null) {
              if (isTransient(e as Exception)) {
                store(
                  path,
                  cached.value,
                  cached.lifetimeMs,
                  cached.expiresAt,
                  cached.failures + 1,
                  cached.lifetimeMs,
                )
              } else {
                entries.remove(path)
              }
            }
            false
          },
        )
      }
    result.fold(
      onSuccess = { load.complete(it.value) },
      onFailure = { load.completeExceptionally(it) },
    )
    if (replaced) onRefreshed(path)
    return result.getOrThrow().value
  }

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
    val due = pushInMs?.takeIf { it > 0 && failures <= MAX_RETRIES }?.let { now() + it }
    val timer =
      due?.let {
        scope.launch {
          // A delay stops in deep sleep and [now] does not, so a long one is cut into steps.
          while (true) delay(minOf(due - now(), MAX_STEP_MS).takeIf { it > 0 } ?: break)
          val timer = coroutineContext[Job]
          if (synchronized(lock) { entries.snapshot()[path]?.timer === timer }) onExpired(path)
        }
      }
    entries.put(path, Entry(value, lifetimeMs, expiresAt, failures, timer))
  }

  /** Its load's first caller was cancelled; whoever joined it asks again. */
  private class Abandoned : Exception()

  companion object {
    /** Failed refreshes of an expired page retried a lifetime apart before it waits for a visit. */
    const val MAX_RETRIES = 3
    private const val MAX_STEP_MS = 60_000L
  }
}
