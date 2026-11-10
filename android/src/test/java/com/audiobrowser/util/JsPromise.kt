package com.audiobrowser.util

import com.margelo.nitro.core.Promise
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/**
 * A Nitro [Promise] needs JNI, so this one is a mock the test settles; settled before anyone
 * listens, it answers each listener as it registers, as Nitro's does.
 */
class JsPromise<T> {
  @Suppress("UNCHECKED_CAST") val promise = mock(Promise::class.java) as Promise<T>
  private var result: Result<T>? = null
  private val onResolved = mutableListOf<(T) -> Unit>()
  private val onRejected = mutableListOf<(Throwable) -> Unit>()

  init {
    doAnswer {
        @Suppress("UNCHECKED_CAST") val listener = it.arguments[0] as (T) -> Unit
        result?.onSuccess(listener) ?: onResolved.add(listener)
        promise
      }
      .`when`(promise)
      .then(any() ?: {})
    doAnswer {
        @Suppress("UNCHECKED_CAST") val listener = it.arguments[0] as (Throwable) -> Unit
        result?.onFailure(listener) ?: onRejected.add(listener)
        promise
      }
      .`when`(promise)
      .catch(any() ?: {})
  }

  fun resolve(value: T) {
    result = Result.success(value)
    onResolved.forEach { it(value) }
  }

  fun reject(error: Throwable) {
    result = Result.failure(error)
    onRejected.forEach { it(error) }
  }
}
