package com.audiobrowser.player

import com.audiobrowser.AudioBrowser
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerBrowserTest {
  private val player = Player(RuntimeEnvironment.getApplication())

  @Test
  fun `a browser connected but not yet configured is awaited until it is`() = runTest {
    val browser = mock(AudioBrowser::class.java)
    player.browser = browser

    val awaited = async { player.awaitBrowser() }
    yield()
    assertFalse(awaited.isCompleted)

    player.notifyBrowserConfigurationReady()
    assertSame(browser, awaited.await())
  }

  @Test
  fun `a browser that replaces a configured one is awaited until it is configured`() = runTest {
    player.browser = mock(AudioBrowser::class.java)
    player.notifyBrowserConfigurationReady()
    val replacement = mock(AudioBrowser::class.java)
    player.browser = replacement

    val awaited = async { player.awaitBrowser() }
    yield()
    assertFalse(awaited.isCompleted)

    player.notifyBrowserConfigurationReady()
    assertSame(replacement, awaited.await())
  }

  @Test
  fun `a cleared browser is not served to callers waiting for its replacement`() = runTest {
    player.browser = mock(AudioBrowser::class.java)
    player.notifyBrowserConfigurationReady()
    player.browser = null

    val awaited = async { player.awaitBrowser() }
    yield()
    assertFalse(awaited.isCompleted)

    val replacement = mock(AudioBrowser::class.java)
    player.browser = replacement
    player.notifyBrowserConfigurationReady()
    assertSame(replacement, awaited.await())
  }
}
