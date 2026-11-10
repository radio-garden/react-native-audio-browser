package com.audiobrowser.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the loop does around its engine that the case file's timelines do not reach. */
class RetryLoopTest {
  private var now = 0L
  private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
  private var reconnects = 0
  private var gaveUp = false
  private var holding = false

  private val loop =
    RetryLoop(
      clock = { now },
      timer = { delayMs, action ->
        val task = (now + delayMs) to action
        tasks += task
        val cancel: () -> Unit = { tasks.remove(task) }
        cancel
      },
      engine =
        object : RetryLoop.Engine {
          override fun reconnect() {
            reconnects++
          }

          override fun awaitNetwork() {}

          override fun holdChanged(held: Boolean) {
            holding = held
          }

          override fun giveUp(cause: PlaybackException?) {
            gaveUp = true
          }
        },
      maxRetries = null,
      recoveryMs = 120_000,
      firstConnectMs = 12_000,
      online = true,
    )

  private fun advanceTo(at: Long) {
    while (true) {
      val next = tasks.filter { it.first <= at }.minByOrNull { it.first } ?: break
      tasks.remove(next)
      now = maxOf(now, next.first)
      next.second()
    }
    now = at
  }

  /** A Load that played from 0 and whose connection ended at [at], its error surfaced. */
  private fun playedAndEnded(at: Long) {
    loop.loadStarted()
    loop.playIntentChanged(true)
    advanceTo(500)
    loop.isPlayingChanged(true)
    advanceTo(at)
    loop.isPlayingChanged(false)
    loop.connectionFailed(inPlace = false, atMs = at)
    assertTrue(loop.onPlayerError(null, stuck = false))
  }

  @Test
  fun `a transient focus loss during a reconnect wait reconnects when it ends`() {
    playedAndEnded(at = 30_000)
    loop.connectionStarted()
    advanceTo(30_100)
    loop.connectionFailed(inPlace = false, atMs = 30_100)
    loop.onPlayerError(null, stuck = false)
    assertEquals(31_100L, loop.reconnectDueMs)

    // The suppression drops the intent and, unlike a pause, prepares nothing when it ends.
    loop.playIntentChanged(false)
    advanceTo(40_000)
    assertEquals(0, reconnects)
    loop.playIntentChanged(true)
    advanceTo(40_000)

    assertEquals(1, reconnects)
  }

  @Test
  fun `a terminal error elsewhere stops the loop until a new Load`() {
    loop.loadStarted()
    loop.playIntentChanged(true)
    assertEquals(120_000L, loop.deadlineMs)

    loop.suspend()
    advanceTo(200_000)

    assertFalse("the old deadline fired", gaveUp)
    assertTrue(loop.stopped)
    loop.loadStarted()
    assertEquals(320_000L, loop.deadlineMs)
  }

  @Test
  fun `the audio a failed connection's buffer plays counts as delivered`() {
    loop.loadStarted()
    loop.playIntentChanged(true)
    advanceTo(300)
    // The burst arrived and the server closed before anything played.
    loop.connectionFailed(inPlace = false, atMs = 300)
    advanceTo(1_000)
    loop.isPlayingChanged(true)
    advanceTo(3_000)
    loop.isPlayingChanged(false)
    loop.onPlayerError(null, stuck = false)

    // Delivered audio reconnects as soon as the 1 s floor allows, not after the first backoff.
    assertEquals(3_000L, loop.reconnectDueMs)
  }

  @Test
  fun `an error that arrives after the engine was stopped is still the loop's`() {
    loop.loadStarted()
    loop.playIntentChanged(true)
    loop.connectionFailed(inPlace = false, atMs = 300)
    loop.playIntentChanged(false)
    loop.engineStopped()

    assertTrue(loop.onPlayerError(null, stuck = false))
    assertNull("a paused loop reconnects nothing", loop.reconnectDueMs)
  }

  @Test
  fun `a connection that starts over a pending failure starts clean`() {
    loop.loadStarted()
    loop.playIntentChanged(true)
    loop.connectionFailed(inPlace = false, atMs = 300)

    loop.connectionStarted()

    assertFalse(loop.failurePending)
    assertFalse(loop.reconnecting)
    loop.connectionFailed(inPlace = false, atMs = 2_000)
    assertTrue("the new connection's end is the loop's", loop.onPlayerError(null, stuck = false))
  }

  @Test
  fun `holds the device awake only while it keeps the idle engine for a play intent`() {
    playedAndEnded(at = 30_000)
    assertTrue("held between connections", holding)

    loop.playIntentChanged(false)
    assertFalse("held over a pause", holding)
    loop.playIntentChanged(true)
    assertTrue(holding)

    advanceTo(31_000)
    loop.connectionStarted()
    assertFalse("held over a connection", holding)

    loop.connectionFailed(inPlace = false, atMs = 31_100)
    loop.onPlayerError(null, stuck = false)
    advanceTo(200_000)
    assertTrue(gaveUp)
    assertFalse("held after the give-up", holding)
  }

  @Test
  fun `a stuck engine reconnects as a plain connection end`() {
    playedAndEnded(at = 30_000)
    advanceTo(30_000)
    loop.connectionStarted()
    advanceTo(31_500)
    loop.isPlayingChanged(true)
    advanceTo(100_000)
    loop.isPlayingChanged(false)

    assertTrue(loop.onPlayerError(null, stuck = true))
    assertEquals(100_000L, loop.reconnectDueMs)
  }
}

class RetryViewTest {
  @Test
  fun `the loop's idle engine reads buffering, a settled one idle`() {
    assertEquals(
      MediaPlayer.STATE_BUFFERING,
      RetryView.engineState(MediaPlayer.STATE_IDLE, true, false, 0),
    )
    assertEquals(
      MediaPlayer.STATE_IDLE,
      RetryView.engineState(MediaPlayer.STATE_IDLE, false, false, 0),
    )
  }

  @Test
  fun `a READY with nothing buffered over a failed connection is the blip`() {
    assertEquals(
      MediaPlayer.STATE_BUFFERING,
      RetryView.engineState(MediaPlayer.STATE_READY, true, true, 0),
    )
  }

  @Test
  fun `a READY with audio buffered is real, failed connection or not`() {
    assertEquals(
      MediaPlayer.STATE_READY,
      RetryView.engineState(MediaPlayer.STATE_READY, true, true, 2_000),
    )
    assertEquals(
      MediaPlayer.STATE_READY,
      RetryView.engineState(MediaPlayer.STATE_READY, false, false, 0),
    )
  }
}
