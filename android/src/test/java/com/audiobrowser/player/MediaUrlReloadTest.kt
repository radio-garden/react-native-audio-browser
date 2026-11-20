package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.exoplayer.ExoPlayer
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.silentWavBytes
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * An ExoPlayer loading through [MediaFactory] a track whose request config mints a new token each
 * time it runs, from a server that records the token and status of every request.
 *
 * SDK 28: from 33, media3 asks AudioManager.getAudioDevicesForAttributes, which Robolectric answers
 * with null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaUrlReloadTest {

  private val context = RuntimeEnvironment.getApplication()
  private val server = MockWebServer()
  private val minted = AtomicInteger()

  /** Tokens up to this one get a 403. */
  private val expired = AtomicInteger()

  /** Tokens that get a body no extractor reads. */
  private val unreadable = CopyOnWriteArrayList<Int>()

  /** Tokens whose response the server cuts off halfway, expiring them as it does. */
  private val dropped = CopyOnWriteArrayList<Int>()

  /** The token and status of each request, in order. */
  private val served = CopyOnWriteArrayList<Pair<Int, Int>>()

  private var player: ExoPlayer? = null
  private var error: PlaybackException? = null

  init {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val token = checkNotNull(request.requestUrl?.queryParameter("token")).toInt()
          val response =
            when {
              token <= expired.get() -> MockResponse().setResponseCode(403)
              token in unreadable -> MockResponse().setBody("not audio")
              else ->
                MockResponse()
                  .setHeader("Content-Type", "audio/wav")
                  .setBody(Buffer().write(silentWavBytes(Duration.ofSeconds(1))))
                  .apply {
                    if (token in dropped) {
                      expired.set(token)
                      setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    }
                  }
            }
          served += token to response.status.split(' ')[1].toInt()
          return response
        }
      }
  }

  @After
  fun shutdown() {
    player?.release()
    server.shutdown()
  }

  @Test
  fun `a reload after a stop never requests the url that expired, with retry on`() {
    assertReloadAfterStopResolvesAfresh(RetryPolicy.Infinite())
  }

  @Test
  fun `a reload after a stop never requests the url that expired, with retry off`() {
    assertReloadAfterStopResolvesAfresh(RetryPolicy.Default)
  }

  @Test
  fun `a reload after an error resolves the url afresh`() {
    unreadable += 1
    val player = load(RetryPolicy.Default)
    runUntil("the unreadable stream fails") { error != null }
    error = null

    player.prepare()
    runUntil("the reload opens the stream") { served.size == 2 || error != null }

    assertNull(error)
    assertEquals(listOf(1 to 200, 2 to 200), served)
  }

  @Test
  fun `an in-place retry after a dropped read never requests the url that expired`() {
    dropped += 1
    load(RetryPolicy.Infinite())
    runUntil("the retry reopens the stream") { served.size == 2 || error != null }

    assertNull(error)
    assertEquals(listOf(1 to 200, 2 to 200), served)
  }

  @Test
  fun `a return to a queue item never requests the url that expired`() {
    val player =
      load(RetryPolicy.Infinite(), "https://media.example/a.wav", "https://media.example/b.wav")
    runUntil("the first track opens") { served.isNotEmpty() || error != null }
    player.seekTo(1, 0)
    runUntil("the second track opens") { served.size >= 2 || error != null }
    expired.set(minted.get())

    player.seekTo(0, 0)
    runUntil("the first track opens again") { served.size >= 3 || error != null }

    assertNull(error)
    // The second track may load again afterwards, as the next item.
    assertEquals(listOf(1 to 200, 2 to 200, 3 to 200), served.take(3))
  }

  private fun assertReloadAfterStopResolvesAfresh(retryPolicy: RetryPolicy) {
    val player = load(retryPolicy)
    runUntil("the stream opens") { served.isNotEmpty() || error != null }
    expired.set(1)

    player.stop()
    player.prepare()
    runUntil("the reload opens the stream") {
      served.lastOrNull()?.second == 200 && served.size > 1 || error != null
    }

    assertNull(error)
    assertEquals(listOf(1 to 200, 2 to 200), served)
  }

  /** Prepares a player on a queue of tracks, paused: nothing here needs their audio. */
  private fun load(
    retryPolicy: RetryPolicy,
    vararg srcs: String = arrayOf("https://media.example/stream.wav"),
  ): ExoPlayer {
    val factory =
      MediaFactory(
        context,
        cache = null,
        retryPolicy,
        getRequestConfig = { _, _ -> tokenConfig(minted.incrementAndGet()) },
      )
    val player = ExoPlayer.Builder(context).setMediaSourceFactory(factory).build()
    this.player = player
    player.addListener(
      object : MediaPlayer.Listener {
        override fun onPlayerError(e: PlaybackException) {
          error = e
        }
      }
    )
    player.setMediaItems(srcs.map { TrackFactory.toMedia3(track(src = it)) })
    player.prepare()
    return player
  }

  private fun runUntil(what: String, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!done()) {
      if (System.currentTimeMillis() > deadline) fail("never: $what; served $served")
      ShadowSystemClock.advanceBy(Duration.ofMillis(10))
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(1)
    }
  }

  private fun tokenConfig(token: Int) =
    MediaRequestConfig(
      resolve = null,
      resolveSync = null,
      transform = null,
      transformSync = null,
      method = null,
      path = "/stream.wav",
      baseUrl = server.url("/").toString().trimEnd('/'),
      headers = null,
      query = mapOf("token" to token.toString()),
      body = null,
      contentType = null,
      userAgent = null,
    )
}
