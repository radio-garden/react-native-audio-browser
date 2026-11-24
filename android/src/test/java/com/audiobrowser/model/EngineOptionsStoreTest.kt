package com.audiobrowser.model

import com.margelo.nitro.audiobrowser.AndroidAudioContentType
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EngineOptionsStoreTest {
  private val context = RuntimeEnvironment.getApplication()

  private val everyFieldSet =
    PlayerSetupOptions(
      minBuffer = 1.5,
      audioContentType = AndroidAudioContentType.SPEECH,
      maxBuffer = 2.0,
      playBuffer = 3.0,
      rebufferBuffer = 4.0,
      backBuffer = 5.0,
      maxCacheSize = 6.0,
      handleAudioBecomingNoisy = false,
      wakeMode = AndroidPlayerWakeMode.NETWORK,
      audioOffload = AudioOffloadOptions(gaplessSupportRequired = false),
      retryPolicy = RetryPolicy.Limited(7, maxRetryDurationMs = 8),
      keepSessionAliveOnError = false,
    )

  @Test
  fun `the saved options load as they were`() {
    for (options in
      listOf(
        everyFieldSet,
        PlayerSetupOptions(),
        PlayerSetupOptions(retryPolicy = RetryPolicy.Infinite(firstConnectMaxRetryDurationMs = 9)),
      )) {
      EngineOptionsStore.save(context, options)
      assertEquals(options, EngineOptionsStore.load(context))
    }
  }

  @Test
  fun `options it cannot store leave none stored`() {
    EngineOptionsStore.save(context, everyFieldSet)
    EngineOptionsStore.save(context, PlayerSetupOptions(maxBuffer = Double.POSITIVE_INFINITY))
    assertNull(EngineOptionsStore.load(context))
  }

  @Test
  fun `nothing loads before a save`() {
    assertNull(EngineOptionsStore.load(context))
  }

  @Test
  fun `options stored under other library defaults load as none`() {
    val stored =
      Json.encodeToString(
        EngineOptionsStore.Stored.serializer(),
        EngineOptionsStore.Stored(
          defaults = PlayerSetupOptions(maxBuffer = 1.0),
          options = everyFieldSet,
        ),
      )
    assertNull(EngineOptionsStore.decode(stored))
  }

  @Test
  fun `a field the stored form lacks, as after an upgrade adds one, loads at its default`() {
    val encoded = Json.parseToJsonElement(EngineOptionsStore.encode(everyFieldSet)).jsonObject
    val stored =
      JsonObject(encoded.mapValues { (_, form) -> JsonObject(form.jsonObject - "maxBuffer") })
        .toString()

    assertEquals(
      everyFieldSet.copy(maxBuffer = PlayerSetupOptions().maxBuffer),
      EngineOptionsStore.decode(stored),
    )
  }

  @Test
  fun `an unreadable stored form loads as none`() {
    val stored = EngineOptionsStore.encode(everyFieldSet).replace("\"NETWORK\"", "\"GONE\"")
    assertNull(EngineOptionsStore.decode(stored))
    assertNull(EngineOptionsStore.decode("not json"))
  }
}
