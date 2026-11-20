package com.audiobrowser

import com.audiobrowser.TestFixtures.browseTrack
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationStartTest {

  private fun TestScope.mainScope() =
    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

  @Test
  fun `a configured path still queries the tabs`() = runTest {
    var queried = false
    val navigated = mutableListOf<String>()

    mainScope().launchConfigurationStart("/home", { arrayOf<Track>().also { queried = true } }) {
      navigated += it
    }
    advanceUntilIdle()

    assertTrue(queried)
    assertEquals(listOf("/home"), navigated)
  }

  @Test
  fun `a navigation that supersedes the start leaves the tabs query running`() = runTest {
    val response = CompletableDeferred<Array<Track>>()
    var resolved = false

    val start =
      mainScope().launchConfigurationStart(null, { response.await().also { resolved = true } }) {}
    start.cancel()
    response.complete(arrayOf(browseTrack(path = "/t")))
    advanceUntilIdle()

    assertTrue(resolved)
  }

  @Test
  fun `without a configured path the start is the first tab's`() = runTest {
    val navigated = mutableListOf<String>()

    mainScope().launchConfigurationStart(null, { arrayOf(browseTrack(path = "/t")) }) {
      navigated += it
    }
    advanceUntilIdle()

    assertEquals(listOf("/t"), navigated)
  }

  @Test
  fun `without a configured path or tabs the start is the root`() = runTest {
    val navigated = mutableListOf<String>()

    mainScope().launchConfigurationStart(null, { error("tabs route failed") }) { navigated += it }
    advanceUntilIdle()

    assertEquals(listOf("/"), navigated)
  }
}
