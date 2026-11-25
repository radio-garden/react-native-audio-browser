package com.audiobrowser.player

import com.audiobrowser.AudioBrowser
import com.audiobrowser.browser.BrowserManager
import com.margelo.nitro.audiobrowser.FailedRequestKind
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerBrowserTest {
  private val player = Player(RuntimeEnvironment.getApplication())

  private val impatientPlayer =
    Player(RuntimeEnvironment.getApplication(), browserRegistrationTimeout = 50.milliseconds)

  @Before
  fun forgetTimeouts() {
    RegistrationTimeouts.take()
  }

  private fun failedBeforeRegistration(): Map<FailedRequestKind, Int> =
    RegistrationTimeouts.take().associate { it.kind to it.count.toInt() }

  private suspend fun Player.timeOut(kind: FailedRequestKind?) {
    try {
      awaitBrowser(kind)
    } catch (_: TimeoutException) {
      return
    }
    throw AssertionError("awaitBrowser($kind) did not time out")
  }

  private fun browser(): AudioBrowser =
    mock(AudioBrowser::class.java).also { `when`(it.browserManager).thenReturn(BrowserManager()) }

  @Test
  fun `a browser connected but not yet configured is awaited until it is`() = runTest {
    val browser = browser()
    player.browser = browser

    val awaited = async { player.awaitBrowser(FailedRequestKind.BROWSE) }
    yield()
    assertFalse(awaited.isCompleted)

    player.notifyBrowserConfigurationReady()
    assertSame(browser, awaited.await())
  }

  @Test
  fun `a browser that replaces a configured one is awaited until it is configured`() = runTest {
    player.browser = browser()
    player.notifyBrowserConfigurationReady()
    val replacement = browser()
    player.browser = replacement

    val awaited = async { player.awaitBrowser(FailedRequestKind.BROWSE) }
    yield()
    assertFalse(awaited.isCompleted)

    player.notifyBrowserConfigurationReady()
    assertSame(replacement, awaited.await())
  }

  @Test
  fun `a cleared browser is not served to callers waiting for its replacement`() = runTest {
    player.browser = browser()
    player.notifyBrowserConfigurationReady()
    player.browser = null

    val awaited = async { player.awaitBrowser(FailedRequestKind.BROWSE) }
    yield()
    assertFalse(awaited.isCompleted)

    val replacement = browser()
    player.browser = replacement
    player.notifyBrowserConfigurationReady()
    assertSame(replacement, awaited.await())
  }

  @Test
  fun `each registration timeout counts under its request's kind`() = runTest {
    impatientPlayer.timeOut(FailedRequestKind.SEARCH)
    impatientPlayer.timeOut(FailedRequestKind.SEARCH)
    impatientPlayer.timeOut(FailedRequestKind.BROWSE)

    assertEquals(
      mapOf(FailedRequestKind.SEARCH to 2, FailedRequestKind.BROWSE to 1),
      failedBeforeRegistration(),
    )
  }

  @Test
  fun `a take returns the timeouts from before the browser registered, then clears them`() =
    runTest {
      impatientPlayer.timeOut(FailedRequestKind.PLAY)
      impatientPlayer.browser = browser()
      impatientPlayer.notifyBrowserConfigurationReady()
      impatientPlayer.awaitBrowser(FailedRequestKind.BROWSE)

      assertEquals(mapOf(FailedRequestKind.PLAY to 1), failedBeforeRegistration())
      assertEquals(emptyMap<FailedRequestKind, Int>(), failedBeforeRegistration())
    }

  @Test
  fun `a wait with no kind counts nothing when it times out`() = runTest {
    impatientPlayer.timeOut(null)

    assertEquals(emptyMap<FailedRequestKind, Int>(), failedBeforeRegistration())
  }

  @Test
  fun `a browser registered in time counts no failure`() = runTest {
    player.browser = browser()
    player.notifyBrowserConfigurationReady()
    player.awaitBrowser(FailedRequestKind.BROWSE)

    assertEquals(emptyMap<FailedRequestKind, Int>(), failedBeforeRegistration())
  }
}
