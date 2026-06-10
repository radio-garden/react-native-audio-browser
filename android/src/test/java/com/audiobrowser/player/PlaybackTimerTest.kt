package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTimerTest {

  @Before
  fun setup() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun playingTimer(onTick: () -> Unit) =
    PlaybackTimer(isActive = { it == PlaybackState.PLAYING }, onTick = onTick)

  @Test
  fun `interval set but not active does not emit`() = runTest {
    var callCount = 0
    val timer = playingTimer { callCount++ }

    timer.setInterval(0.1) // active is still false (no state change)

    advanceTimeBy(500)
    assertEquals(0, callCount)
  }

  @Test
  fun `active without interval does nothing`() = runTest {
    var callCount = 0
    val timer = playingTimer { callCount++ }

    timer.onPlaybackStateChanged(PlaybackState.PLAYING)

    advanceTimeBy(500)
    assertEquals(0, callCount)
  }

  @Test
  fun `active and interval set begins emitting, leaving active ceases`() = runTest {
    var callCount = 0
    val timer = playingTimer { callCount++ }

    timer.setInterval(0.1)
    timer.onPlaybackStateChanged(PlaybackState.PLAYING)

    advanceTimeBy(350)
    assertTrue("Expected events while active", callCount > 0)

    timer.onPlaybackStateChanged(PlaybackState.PAUSED)
    val afterPause = callCount

    advanceTimeBy(500)
    assertEquals(afterPause, callCount)
  }

  @Test
  fun `changing interval while running restarts with new interval`() = runTest {
    var callCount = 0
    val timer = playingTimer { callCount++ }

    timer.setInterval(0.1)
    timer.onPlaybackStateChanged(PlaybackState.PLAYING)
    advanceTimeBy(350)
    assertTrue("Expected events while running, got $callCount", callCount > 0)

    val before = callCount
    timer.setInterval(0.2) // different value — should restart
    advanceTimeBy(500)
    assertTrue("Expected more events after interval change", callCount > before)

    timer.setInterval(null)
  }

  @Test
  fun `nil interval stops`() = runTest {
    var callCount = 0
    val timer = playingTimer { callCount++ }

    timer.setInterval(0.1)
    timer.onPlaybackStateChanged(PlaybackState.PLAYING)
    advanceTimeBy(350)
    assertTrue("Expected events before clearing", callCount > 0)

    timer.setInterval(null)
    val afterNil = callCount

    advanceTimeBy(500)
    assertEquals(afterNil, callCount)
  }

  @Test
  fun `isActive predicate gates which states run`() = runTest {
    // playing-only timer must NOT tick while buffering...
    var playingOnly = 0
    val strict = playingTimer { playingOnly++ }
    strict.setInterval(0.1)
    strict.onPlaybackStateChanged(PlaybackState.BUFFERING)
    advanceTimeBy(350)
    assertEquals(0, playingOnly)
    strict.setInterval(null)

    // ...while a progress-style predicate that includes buffering does.
    var progress = 0
    val lenient =
      PlaybackTimer(
        isActive = {
          it == PlaybackState.LOADING ||
            it == PlaybackState.BUFFERING ||
            it == PlaybackState.PLAYING
        }
      ) {
        progress++
      }
    lenient.setInterval(0.1)
    lenient.onPlaybackStateChanged(PlaybackState.BUFFERING)
    advanceTimeBy(350)
    assertTrue("Expected events when buffering with lenient predicate", progress > 0)
    lenient.setInterval(null)
  }
}
