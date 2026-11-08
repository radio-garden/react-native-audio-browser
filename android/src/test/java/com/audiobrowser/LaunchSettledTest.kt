package com.audiobrowser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchSettledTest {

  @Test
  fun `a call into a cancelled scope settles without running`() = runTest {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler)).apply { cancel() }
    val outcomes = mutableListOf<Result<Int>>()
    var ran = false

    scope.launchSettled({ outcomes += it }) {
      ran = true
      1
    }
    advanceUntilIdle()

    assertFalse(ran)
    assertEquals(1, outcomes.size)
    assertTrue(outcomes.single().exceptionOrNull() is CancellationException)
  }

  @Test
  fun `a call whose scope is cancelled before it is dispatched settles without running`() =
    runTest {
      val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
      val outcomes = mutableListOf<Result<Int>>()
      var ran = false

      scope.launchSettled({ outcomes += it }) {
        ran = true
        1
      }
      scope.cancel()
      advanceUntilIdle()

      assertFalse(ran)
      assertEquals(1, outcomes.size)
      assertTrue(outcomes.single().exceptionOrNull() is CancellationException)
    }

  @Test
  fun `a call cancelled while it waits settles once`() = runTest {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    val outcomes = mutableListOf<Result<Unit>>()

    scope.launchSettled({ outcomes += it }) { CompletableDeferred<Unit>().await() }
    advanceUntilIdle()
    scope.cancel()
    advanceUntilIdle()

    assertEquals(1, outcomes.size)
    assertTrue(outcomes.single().exceptionOrNull() is CancellationException)
  }

  @Test
  fun `a call settles with what it returns or throws`() = runTest {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    val outcomes = mutableListOf<Result<Int>>()
    val boom = IllegalStateException("boom")

    scope.launchSettled({ outcomes += it }) { 1 }
    scope.launchSettled({ outcomes += it }) { throw boom }
    advanceUntilIdle()

    assertEquals(listOf(Result.success(1), Result.failure<Int>(boom)), outcomes)
  }
}
