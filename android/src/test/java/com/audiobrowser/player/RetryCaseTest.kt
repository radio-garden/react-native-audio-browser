package com.audiobrowser.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Runs every case in spec/retry-cases.json, which build.gradle puts on the test classpath. */
@RunWith(Parameterized::class)
class RetryCaseTest(@Suppress("unused") private val name: String, private val case: JsonObject) {
  companion object {
    private val CASE_KEYS = setOf("name", "maxRetries", "recoveryMs", "firstConnectMs", "steps")
    private val STEP_KEYS = setOf("at", "event", "delivered", "startedAt", "deadline", "wait")

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> {
      val text =
        checkNotNull(RetryCaseTest::class.java.getResource("/retry-cases.json")) {
            "spec/retry-cases.json is not on the test classpath"
          }
          .readText()
      val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
      check(cases.isNotEmpty()) { "spec/retry-cases.json holds no case" }
      return cases.map {
        arrayOf(it.jsonObject.getValue("name").jsonPrimitive.content, it.jsonObject)
      }
    }

    /** An integer, not a numeric string or a fraction. */
    private fun JsonElement.strictLong(what: String): Long {
      val primitive = this as? JsonPrimitive
      return primitive?.takeUnless { it.isString }?.longOrNull ?: error("$what is not an integer")
    }
  }

  @Test
  fun runs() {
    (case.keys - CASE_KEYS).firstOrNull()?.let { error("unknown key $it") }
    val budget = SilenceBudget(online = true)
    case["recoveryMs"]?.let { budget.recoveryMs = it.strictLong("recoveryMs") }
    case["firstConnectMs"]?.let { budget.firstConnectMs = it.strictLong("firstConnectMs") }
    val pacer = ReconnectPacer(maxRetries = case["maxRetries"]?.strictLong("maxRetries")?.toInt())
    var online = true
    var lastAt = Long.MIN_VALUE
    for (element in case.getValue("steps").jsonArray) {
      // Strict, so a misspelt or missing key fails the case instead of dropping an assertion.
      val step = element.jsonObject
      (step.keys - STEP_KEYS).firstOrNull()?.let { error("unknown key $it") }
      val at = step.getValue("at").strictLong("at")
      check(at >= lastAt) { "at $at: time runs backwards" }
      lastAt = at
      val event = step.getValue("event").jsonPrimitive.content
      when (event) {
        "load" -> {
          budget.loadStarted(at)
          pacer.reset()
        }
        "intentUp" -> {
          budget.playIntentChanged(true, at)
          pacer.reset()
        }
        "intentDown" -> budget.playIntentChanged(false, at)
        "audioStarts" -> budget.audioStarted(at)
        "audioStops" -> budget.audioStopped(at)
        "offline" -> {
          online = false
          budget.onlineChanged(false)
        }
        "online" -> {
          online = true
          budget.onlineChanged(true)
          pacer.networkReturned()
        }
        "ended" -> {
          check("wait" in step) { "at $at: an end states the wait" }
          val deliveredAudio =
            when (val delivered = step["delivered"]?.jsonPrimitive?.content) {
              "audio" -> true
              "nothing" -> false
              else -> error("at $at: an end delivers audio or nothing, not $delivered")
            }
          val startedAt =
            checkNotNull(step["startedAt"]) { "at $at: an end states when it started" }
          budget.connectionEnded(at)
          val startedAtMs = startedAt.strictLong("startedAt")
          check(startedAtMs <= at) { "at $at: started after it ended" }
          pacer.connectionEnded(deliveredAudio, startedAtMs, at, online)
        }
        else -> error("at $at: unknown event $event")
      }
      val deadline = checkNotNull(step["deadline"]) { "at $at: every step states its deadline" }
      assertEquals(
        "deadline at $at ($event)",
        if (deadline is JsonNull) null else deadline.strictLong("deadline at $at"),
        budget.deadlineMs,
      )
      step["wait"]?.let { wait ->
        val expected =
          if (wait is JsonPrimitive && wait.isString) {
            when (wait.content) {
              "online" -> ReconnectWait.UntilOnline
              "giveUp" -> ReconnectWait.GiveUp
              else -> error("at $at: unknown wait ${wait.content}")
            }
          } else {
            ReconnectWait.After(wait.strictLong("wait at $at"))
          }
        assertEquals("wait at $at ($event)", expected, pacer.wait(at, online))
      }
    }
  }
}
