package com.audiobrowser.player

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.goOnline
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.silentWavBytes
import com.audiobrowser.silentWavUri
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackState
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock

// SDK 28 and the disabled audio track as in PlayerSuppressionTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerRetryTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val connectivity =
    shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
  private val wifi = ShadowNetwork.newInstance(103)
  private val player = Player(context)
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val callbacks = mock(Callbacks::class.java)

  private val servers = mutableListOf<ServerSocket>()

  @After
  fun release() {
    servers.forEach { it.close() }
    scope.cancel()
    player.networkMonitor.destroy()
    player.exoPlayer.release()
  }

  @Test
  fun `a terminal error ends the load`() {
    play(folder.silentWavUri(Duration.ofMinutes(1)))
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    player.playerListener.onPlayerError(
      PlaybackException("failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    )

    assertEquals(PlaybackState.ERROR, player.playbackState)
  }

  @Test
  fun `a pause keeps a terminal error`() {
    failTerminally()
    val failed = player.getPlayback()

    player.pause()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(failed, player.getPlayback())
    verify(callbacks, never()).onPlaybackError(null)
  }

  @Test
  fun `a toggle after a terminal error reloads`() {
    failTerminally()

    player.togglePlayback()

    assertTrue(player.exoPlayer.playWhenReady)
    assertEquals(MediaPlayer.STATE_BUFFERING, player.exoPlayer.playbackState)
  }

  @Test
  fun `a pause during a retry stays paused and the next play reloads`() {
    pauseDuringRetry { player.pause() }

    player.play()
    awaitRetry("the reload's retry")
  }

  @Test
  fun `a session pause during a retry stays paused and a session play reloads`() {
    pauseDuringRetry { player.forwardingPlayer.pause() }

    // Masked as paused and ready, so the session's play button doesn't prepare it itself.
    assertEquals(MediaPlayer.STATE_READY, player.forwardingPlayer.playbackState)
    player.forwardingPlayer.play()
    awaitRetry("the reload's retry")
  }

  @Test
  fun `a live pause idles the engine paused, and the next play reconnects`() {
    play(folder.silentWavUri(Duration.ofMinutes(1)), live = true)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    player.pause()
    assertStaysPausedAndIdle()
    player.prepare()
    player.seekToLiveEdge()
    assertStaysPausedAndIdle()
    // A seek while paused, as far into a live window as the reconnect must not resume.
    player.seekTo(30, TimeUnit.SECONDS)
    // Masked as paused and ready, so the session shows the station paused.
    assertEquals(MediaPlayer.STATE_READY, player.forwardingPlayer.playbackState)

    player.play()
    idleUntil("the reconnect") {
      player.exoPlayer.playbackState != MediaPlayer.STATE_IDLE &&
        player.playbackState in
          setOf(PlaybackState.LOADING, PlaybackState.BUFFERING, PlaybackState.PLAYING)
    }
    assertTrue(player.exoPlayer.currentPosition < 30_000)
  }

  @Test
  fun `an on-demand pause keeps the engine loaded`() {
    play(folder.silentWavUri(Duration.ofMinutes(1)))
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    player.pause()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(PlaybackState.PAUSED, player.playbackState)
    assertEquals(MediaPlayer.STATE_READY, player.exoPlayer.playbackState)
  }

  /** Pauses with a retry pending and runs past every backoff it would have taken. */
  private fun pauseDuringRetry(vararg next: String, pause: () -> Unit) {
    play("https://127.0.0.1:9/stream", *next, retryPolicy = RetryPolicy.Infinite())
    awaitRetry("a pending retry")

    pause()
    assertStaysPausedAndIdle()
  }

  /** Runs past every backoff a retry would have taken. */
  private fun assertStaysPausedAndIdle() {
    repeat(10) {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      Thread.sleep(20)
      shadowOf(Looper.getMainLooper()).idle()
    }

    assertEquals(Playback(PlaybackState.PAUSED, null), player.getPlayback())
    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertFalse(
      "a terminal error was reported",
      mockingDetails(callbacks).invocations.any {
        it.method.name == "onPlaybackError" && it.arguments[0] != null
      },
    )
  }

  private fun awaitRetry(what: String) =
    idleUntil(what) {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      player.playbackError?.retrying == true
    }

  @Test
  fun `removing a failed current track plays the next`() {
    failTerminally(folder.silentWavUri(Duration.ofMinutes(1)))

    player.remove(listOf(0))

    idleUntil("the next track playing") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `removing another track keeps a terminal error`() {
    failTerminally(folder.silentWavUri(Duration.ofMinutes(1)))

    player.remove(listOf(1))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.ERROR, player.playbackState)
  }

  @Test
  fun `removing the current track after a cancelled retry stays idle and paused`() {
    pauseDuringRetry(folder.silentWavUri(Duration.ofMinutes(1))) { player.pause() }

    player.remove(listOf(0))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
    assertEquals(MediaPlayer.STATE_READY, player.forwardingPlayer.playbackState)
  }

  @Test
  fun `removing a failed current track while paused is paused`() {
    failTerminally(folder.silentWavUri(Duration.ofMinutes(1)))
    player.pause()
    shadowOf(Looper.getMainLooper()).idle()

    player.remove(listOf(0))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(Playback(PlaybackState.PAUSED, null), player.getPlayback())
  }

  @Test
  fun `adding a track after a cancelled retry stays idle and paused`() {
    pauseDuringRetry { player.pause() }

    player.add(TestFixtures.track(src = folder.silentWavUri(Duration.ofMinutes(1))))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
  }

  @Test
  fun `adding to an empty queue loads`() {
    player.setup(PlayerSetupOptions())

    player.add(TestFixtures.track(src = folder.silentWavUri(Duration.ofMinutes(1))))
    shadowOf(Looper.getMainLooper()).idle()

    assertNotEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
  }

  @Test
  fun `a stop after a cancelled retry tells the session`() {
    pauseDuringRetry { player.pause() }
    val states = mutableListOf<Int>()
    player.forwardingPlayer.addListener(
      object : MediaPlayer.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          states += playbackState
        }
      }
    )

    player.stop()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.forwardingPlayer.playbackState)
    assertEquals(listOf(MediaPlayer.STATE_IDLE), states)
  }

  @Test
  fun `a session stop after a cancelled retry is a stop the session sees`() {
    pauseDuringRetry { player.pause() }
    val states = mutableListOf<Int>()
    player.forwardingPlayer.addListener(
      object : MediaPlayer.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          states += playbackState
        }
      }
    )

    player.forwardingPlayer.stop()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(PlaybackState.STOPPED, player.playbackState)
    assertEquals(MediaPlayer.STATE_IDLE, player.forwardingPlayer.playbackState)
    assertEquals(listOf(MediaPlayer.STATE_IDLE), states)
  }

  @Test
  fun `a load failing while paused stays paused and the next play reloads`() {
    val server = ServerSocket(0)
    val wav = silentWavBytes(Duration.ofMinutes(1))
    val sent = CountDownLatch(1)
    val drop = CountDownLatch(1)
    // Serves five seconds of the minute, holds the connection until the drop, then refuses.
    thread(isDaemon = true) {
      server.accept().use { socket ->
        val request = socket.getInputStream().bufferedReader()
        while (!request.readLine().isNullOrEmpty()) Unit
        socket.getOutputStream().run {
          write("HTTP/1.1 200 OK\r\nContent-Length: ${wav.size}\r\n\r\n".toByteArray())
          write(wav, 0, 40_000)
          flush()
        }
        sent.countDown()
        drop.await(10, TimeUnit.SECONDS)
      }
      server.close()
    }
    play("http://127.0.0.1:${server.localPort}/stream.wav", retryPolicy = RetryPolicy.Infinite())
    // A source with no track enabled stops loading, and the load must still run when it fails.
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .build()
    idleUntil("the first bytes") { sent.count == 0L }

    player.pause()
    shadowOf(Looper.getMainLooper()).idle()
    drop.countDown()
    idleUntil("the load given up") { player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE }
    assertStaysPausedAndIdle()

    player.play()
    awaitRetry("the reload's retry")
  }

  @Test
  fun `emptying the queue clears a terminal error`() {
    failTerminally()

    player.clear()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(Playback(PlaybackState.NONE, null), player.getPlayback())
    verify(callbacks).onPlaybackError(null)
    verify(callbacks).onPlaybackChanged(Playback(PlaybackState.NONE, null))
  }

  @Test
  fun `a player that gave up stays stopped when the network returns`() {
    connectivity.setNetworkCapabilities(wifi, capabilities(internet = false))
    connectivity.networkCallbacks.forEach { it.onAvailable(wifi) }
    // A closed port: the load fails at once, the retry is pending, and the next failure is
    // terminal.
    play("https://127.0.0.1:9/stream", retryPolicy = RetryPolicy.Limited(maxRetries = 1))
    player.observeNetworkConnectivity(scope)
    idleUntil("the retry gave up") {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      player.playbackState == PlaybackState.ERROR
    }
    assertTrue(player.exoPlayer.playWhenReady)
    assertTrue(
      "a retry was pending",
      mockingDetails(callbacks).invocations.any {
        it.method.name == "onPlaybackChanged" &&
          (it.arguments[0] as Playback).error?.retrying == true
      },
    )

    connectivity.setNetworkCapabilities(wifi, capabilities(internet = true))
    connectivity.networkCallbacks.forEach {
      it.onCapabilitiesChanged(wifi, capabilities(internet = true))
    }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.ERROR, player.playbackState)
  }

  @Test
  fun `the wake mode's lock is held while the loop keeps the idle engine, and let go on pause`() {
    goOnline(context)
    play(
      "https://127.0.0.1:9/stream",
      retryPolicy = RetryPolicy.Infinite(),
      wakeMode = AndroidPlayerWakeMode.NETWORK,
    )
    idleUntil("the loop holding the idle engine") {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      player.retryLoop!!.reconnecting && player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE
    }
    assertTrue("no wake lock while held", player.retryLocks!!.wakeLockHeld)

    player.pause()
    shadowOf(Looper.getMainLooper()).idle()

    assertFalse("a wake lock over a pause", player.retryLocks!!.wakeLockHeld)
  }

  @Test
  fun `a track change on a stopped engine starts no Load until it is prepared`() {
    goOnline(context)
    val wav = folder.silentWavUri(Duration.ofMinutes(1))
    play(wav, wav, retryPolicy = RetryPolicy.Infinite())
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
    player.exoPlayer.stop()

    player.exoPlayer.seekToNextMediaItem()
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue("a deadline runs for a track nothing connects", player.retryLoop!!.stopped)
  }

  @Test
  fun `another app taking the focus during a reconnect wait pauses, and nothing takes it back`() {
    val connections = holdIdleEngine()

    focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    val after = connections.get()
    advanceSeconds(10)

    assertFalse(player.playWhenReady)
    assertEquals("a reconnect took the focus back", after, connections.get())
    assertEquals(PlaybackState.PAUSED, player.playbackState)
  }

  @Test
  fun `a transient focus loss during a reconnect wait holds the reconnect until the focus returns`() {
    val connections = holdIdleEngine()

    focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    shadowOf(Looper.getMainLooper()).idle()
    val after = connections.get()
    advanceSeconds(10)
    assertEquals("a reconnect during the loss", after, connections.get())
    assertTrue(player.playWhenReady)

    focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
    idleUntil("a reconnect once the focus returned") {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      connections.get() > after
    }
  }

  /** Plays from a server that ends each connection unanswered, until the loop holds the engine. */
  private fun holdIdleEngine(): AtomicInteger {
    goOnline(context)
    val server = ServerSocket(0)
    servers += server
    val connections = AtomicInteger()
    thread(isDaemon = true) {
      while (!server.isClosed) {
        try {
          server.accept().close()
          connections.incrementAndGet()
        } catch (_: IOException) {
          return@thread
        }
      }
    }
    play("http://127.0.0.1:${server.localPort}/live", retryPolicy = RetryPolicy.Infinite())
    idleUntil("the loop holding the engine") {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      player.retryLoop!!.reconnecting && player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE
    }
    val audio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    assertNotSame(
      "no focus held during the wait",
      audio.lastAbandonedAudioFocusRequest,
      audio.lastAudioFocusRequest.audioFocusRequest,
    )
    return connections
  }

  /** The listener of the last focus request: the retry loop's, while it holds the engine. */
  private fun focusListener() =
    shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
      .lastAudioFocusRequest
      .listener

  @Test
  fun `a skip while the loop holds the idle engine connects the next track`() {
    goOnline(context)
    val server = ServerSocket(0)
    val paths = CopyOnWriteArrayList<String>()
    // Reads each request line and ends the connection unanswered.
    thread(isDaemon = true) {
      while (!server.isClosed) {
        try {
          server.accept().use { paths += it.getInputStream().bufferedReader().readLine() }
        } catch (_: IOException) {
          return@thread
        }
      }
    }
    val base = "http://127.0.0.1:${server.localPort}"
    play("$base/one", "$base/two", retryPolicy = RetryPolicy.Infinite())
    idleUntil("the loop holding the engine") {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      player.retryLoop!!.reconnecting && player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE
    }

    player.forwardingPlayer.seekToNext()

    idleUntil("a request for the next track") {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      paths.any { it.contains("/two") }
    }
    server.close()
  }

  @Test
  fun `a failure report that arrives after a reconnect is not the new connection's`() {
    goOnline(context)
    play(folder.silentWavUri(Duration.ofMinutes(1)), retryPolicy = RetryPolicy.Infinite())
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    // Posted from the playback thread before the go-live reconnect, run after it.
    player.onLoadFailure(IOException("closed"), inPlace = false)
    player.localEngine.reconnectAtLiveEdge()
    shadowOf(Looper.getMainLooper()).idle()

    assertFalse(player.retryLoop!!.failurePending)
    assertEquals(null, player.playbackError)
  }

  @Test
  fun `with retry, a stuck engine reconnects and never ends in an error`() {
    goOnline(context)
    play(folder.silentWavUri(Duration.ofMinutes(1)), retryPolicy = RetryPolicy.Infinite())
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    // As media3 leaves a stuck engine: idle, with the error.
    player.exoPlayer.stop()
    player.playerListener.onPlayerError(stuck())
    advanceSeconds(2)

    assertNotEquals("no reconnect", MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(null, player.playbackError)
    assertNotEquals(PlaybackState.ERROR, player.playbackState)
  }

  @Test
  fun `a title after a reconnect inside the retry loop shows`() {
    goOnline(context)
    play(folder.silentWavUri(Duration.ofMinutes(1)), retryPolicy = RetryPolicy.Infinite())
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
    player.exoPlayer.stop()
    player.playerListener.onPlayerError(stuck())
    advanceSeconds(2)
    assertNotEquals("no reconnect", MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)

    player.playerListener.onMetadata(Metadata(IcyInfo(ByteArray(0), "Song", null)))

    assertEquals("Song", player.nowPlaying.timedMetadata?.title)
  }

  @Test
  fun `with retry off, a stuck engine ends in the stalled error`() {
    play(folder.silentWavUri(Duration.ofMinutes(1)))
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    player.playerListener.onPlayerError(stuck())

    assertEquals(PlaybackState.ERROR, player.playbackState)
    assertEquals("playback-stalled", player.playbackError?.code)
  }

  private fun stuck() =
    PlaybackException(
      "stuck",
      StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, 60_000),
      PlaybackException.ERROR_CODE_TIMEOUT,
    )

  private fun advanceSeconds(seconds: Int) =
    repeat(seconds) {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }

  /** A closed port: the load fails at once, and with no retries the failure is terminal. */
  private fun failTerminally(vararg next: String) {
    play("https://127.0.0.1:9/stream", *next, retryPolicy = RetryPolicy.Limited(maxRetries = 0))
    idleUntil("a terminal error") {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      player.playbackState == PlaybackState.ERROR
    }
  }

  private fun play(
    vararg srcs: String,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
    wakeMode: AndroidPlayerWakeMode = AndroidPlayerWakeMode.NONE,
    live: Boolean? = null,
  ) {
    player.setup(PlayerSetupOptions(retryPolicy = retryPolicy, wakeMode = wakeMode))
    player.setCallbacks(callbacks)
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.exoPlayer.setMediaItems(
      srcs.mapIndexed { i, src ->
        TrackFactory.toMedia3(TestFixtures.track(title = "$i", src = src, live = live))
      }
    )
    player.exoPlayer.prepare()
    player.play()
  }

  private fun capabilities(internet: Boolean) =
    ShadowNetworkCapabilities.newInstance().also {
      if (internet) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

  private fun idleUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline)
        fail(
          "timed out waiting for $what: ${player.playbackState}, " +
            "exo ${player.exoPlayer.playbackState}, ${player.exoPlayer.playerError}"
        )
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }
}
