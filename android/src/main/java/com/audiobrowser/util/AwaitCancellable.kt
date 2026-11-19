package com.audiobrowser.util

import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** How long a JS callback may take where its caller falls back on a failure. */
internal val FALLBACK_TIMEOUT = 10.seconds

/** Awaits this JS promise. Unlike Nitro's `await`, a cancelled caller resumes at once. */
internal suspend fun <T> Promise<T>.awaitCancellable(): T = settled()

/** [awaitCancellable] for a JS callback that returns a promise, behind Nitro's bridge promise. */
internal suspend fun <T> Promise<Promise<T>>.awaitNested(): T = settled().settled()

/**
 * [awaitCancellable], failing with a [TimeoutException], which is not a cancellation, after
 * [FALLBACK_TIMEOUT].
 */
internal suspend fun <T> Promise<T>.awaitOrTimeout(): T = withTimeoutAsFailure { settled() }

/** [awaitNested], failing as [awaitOrTimeout] does; the timeout covers both promises. */
internal suspend fun <T> Promise<Promise<T>>.awaitNestedOrTimeout(): T = withTimeoutAsFailure {
  settled().settled()
}

private suspend fun <T> withTimeoutAsFailure(block: suspend () -> T): T =
  try {
    withTimeout(FALLBACK_TIMEOUT) { block() }
  } catch (e: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw TimeoutException("JS callback outran $FALLBACK_TIMEOUT")
  }

private suspend fun <T> Promise<T>.settled(): T = suspendCancellableCoroutine { c ->
  then { c.resume(it) }
  catch { c.resumeWithException(it) }
}
