package com.audiobrowser.util

import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
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

/**
 * [awaitCancellable] for a promise a JS callback returned, whose rejection Nitro gives as `Name:
 * message`: it fails with the message alone when `Name` is a JS error name.
 */
internal suspend fun <T> Promise<T>.awaitJsPromise(): T = settled(::withoutErrorName)

/** [awaitCancellable] for a JS callback that returns a promise, behind Nitro's bridge promise. */
internal suspend fun <T> Promise<Promise<T>>.awaitNested(): T = settled().awaitJsPromise()

/**
 * [awaitCancellable], failing with a [TimeoutException], which is not a cancellation, after
 * [FALLBACK_TIMEOUT].
 */
internal suspend fun <T> Promise<T>.awaitOrTimeout(): T =
  withTimeoutAsFailure(FALLBACK_TIMEOUT, "JS callback") { settled() }

/** [awaitNested], failing as [awaitOrTimeout] does; the timeout covers both promises. */
internal suspend fun <T> Promise<Promise<T>>.awaitNestedOrTimeout(): T =
  withTimeoutAsFailure(FALLBACK_TIMEOUT, "JS callback") { awaitNested() }

/**
 * [withTimeout], failing with a [TimeoutException] rather than cancelling: a caller's `future` then
 * fails, where a cancellation would end it without a word. [what] names it in the message.
 */
internal suspend fun <T> withTimeoutAsFailure(
  timeout: Duration,
  what: String,
  block: suspend () -> T,
): T =
  try {
    withTimeout(timeout) { block() }
  } catch (e: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw TimeoutException("$what outran $timeout")
  }

private suspend fun <T> Promise<T>.settled(rejection: (Throwable) -> Throwable = { it }): T =
  suspendCancellableCoroutine { c ->
    then { c.resume(it) }
    catch { c.resumeWithException(rejection(it)) }
  }

private val JS_ERROR_NAME = Regex("""^(?:[A-Za-z_$][A-Za-z0-9_$]*)?Error: """)

private fun withoutErrorName(rejection: Throwable): Throwable {
  val message = rejection.message ?: return rejection
  val name = JS_ERROR_NAME.find(message) ?: return rejection
  return RuntimeException(message.substring(name.range.last + 1), rejection)
}
