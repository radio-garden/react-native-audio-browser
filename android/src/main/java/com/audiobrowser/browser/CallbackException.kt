package com.audiobrowser.browser

import kotlinx.coroutines.CancellationException

class CallbackException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs [block], a call into a JS callback: what it throws becomes a [CallbackException] with its
 * message, except a cancellation and a [CallbackException], which pass through.
 */
internal suspend fun <T> fromCallback(block: suspend () -> T): T =
  try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: CallbackException) {
    throw e
  } catch (e: Exception) {
    throw CallbackException(e.message ?: e.toString(), e)
  }
