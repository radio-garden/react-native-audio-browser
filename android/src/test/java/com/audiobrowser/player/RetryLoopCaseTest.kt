package com.audiobrowser.player

import androidx.media3.common.PlaybackException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Runs every case in spec/retry-cases.json through [RetryLoop], with a fake clock, timer and
 * engine: each connection's start is the loop's own reconnect, checked against the case's
 * `startedAt`.
 */
@RunWith(Parameterized::class)
class RetryLoopCaseTest(private val name: String, private val case: JsonObject) {
  companion object {
    @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = RetryCaseTest.cases()

    private fun JsonPrimitive.long(what: String): Long =
      takeUnless { it.isString }?.longOrNull ?: error("$what is not an integer")
  }

  private var now = 0L
  private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
  private var lastStartAt: Long? = null
  private var audioSinceStart = false
  private var audible = false
  private var engineIdle = false
  private var gaveUp = false
  private var intentUp = false

  /** A failure since audio last played, which Player shows as a retrying error. */
  private var retrying = false

  private val loop by lazy {
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
          override fun reconnect() = start()

          override fun awaitNetwork() {}

          override fun holdChanged(held: Boolean) {}

          override fun giveUp(cause: PlaybackException?) {
            gaveUp = true
            engineIdle = true
          }
        },
      maxRetries = case["maxRetries"]?.jsonPrimitive?.long("maxRetries")?.toInt(),
      recoveryMs = case["recoveryMs"]?.jsonPrimitive?.long("recoveryMs") ?: 120_000,
      firstConnectMs = case["firstConnectMs"]?.jsonPrimitive?.long("firstConnectMs") ?: 12_000,
      online = true,
    )
  }

  private fun start() {
    engineIdle = false
    lastStartAt = now
    audioSinceStart = audible
    loop.connectionStarted()
  }

  /** Runs the timer's tasks due by [until], each at its own time. */
  private fun runUntil(until: Long) {
    while (true) {
      val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
      tasks.remove(next)
      now = maxOf(now, next.first)
      next.second()
    }
    now = until
  }

  private fun expectedDeadline(step: JsonObject): Long? =
    when (val deadline = step.getValue("deadline")) {
      is JsonNull -> null
      else -> (deadline as JsonPrimitive).long("deadline")
    }

  @Test
  fun runs() {
    for (element in case.getValue("steps").jsonArray) {
      val step = element as JsonObject
      val at = step.getValue("at").jsonPrimitive.long("at")
      runUntil(at)
      val event = step.getValue("event").jsonPrimitive.content
      when (event) {
        "load" -> {
          gaveUp = false
          engineIdle = false
          lastStartAt = at
          audible = false
          audioSinceStart = false
          loop.loadStarted()
        }
        "intentUp",
        "intentDown" -> {
          val up = event == "intentUp"
          val changed = up != intentUp
          intentUp = up
          loop.playIntentChanged(up)
          // As Player does: a pause over a retrying error stops the engine, and a play prepares an
          // idle one.
          if (!up && retrying) engineIdle = true
          if (up && changed && engineIdle && !gaveUp) start()
        }
        "audioStarts",
        "audioStops" -> {
          audible = event == "audioStarts"
          if (audible) {
            audioSinceStart = true
            retrying = false
          }
          loop.isPlayingChanged(audible)
        }
        "offline",
        "online" -> loop.onlineChanged(event == "online")
        "ended" -> {
          retrying = true
          // A deadline already passed gave up when it did: there is no connection to end.
          if (gaveUp && expectedDeadline(step)?.let { it <= at } == true) continue
          assertEquals(
            "at $at: the connection that ended started then",
            step.getValue("startedAt").jsonPrimitive.long("startedAt"),
            lastStartAt,
          )
          assertEquals(
            "at $at: what the connection delivered",
            step.getValue("delivered").jsonPrimitive.content == "audio",
            audioSinceStart,
          )
          loop.connectionFailed(inPlace = false, atMs = at)
          engineIdle = true
          assertTrue("at $at: the loop takes the error", loop.onPlayerError(null, stuck = false))
        }
        else -> error("at $at: unknown event $event")
      }
      val deadline = expectedDeadline(step)
      assertEquals("deadline at $at ($event)", deadline, loop.deadlineMs)
      step["wait"]?.let { wait ->
        wait as JsonPrimitive
        when {
          wait.isString && wait.content == "giveUp" -> assertTrue("gave up at $at", gaveUp)
          // A deadline in the past gives up at once, whatever the pacer would wait.
          deadline != null && deadline <= at -> {
            runUntil(at)
            assertTrue("gave up at $at, past the deadline", gaveUp)
          }
          wait.isString && wait.content == "online" -> {
            assertFalse("at $at: gave up", gaveUp)
            assertEquals("at $at: no reconnect is scheduled", null, loop.reconnectDueMs)
            // On both branches: with a connection still open, the wait applies once it ends.
            if (engineIdle) assertTrue("at $at: waits for the network", loop.reconnecting)
          }
          else -> {
            assertFalse("at $at: gave up", gaveUp)
            if (engineIdle)
              assertEquals("reconnect at $at", at + wait.long("wait"), loop.reconnectDueMs)
          }
        }
      }
      runUntil(at)
    }
  }
}
