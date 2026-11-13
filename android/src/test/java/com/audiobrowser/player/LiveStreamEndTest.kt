package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.datasource.DataSourceException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.silentWavBytes
import com.audiobrowser.util.TrackFactory
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

/**
 * A progressive stream that the server ends, served without a length, loaded through
 * [MediaFactory]: by media3's own media period, whose samples the test reads as a renderer would,
 * advancing the clock by the audio it reads since Robolectric plays no audio, or by an ExoPlayer
 * whose one renderer reads them.
 */
@RunWith(RobolectricTestRunner::class)
class LiveStreamEndTest {

  private val context = RuntimeEnvironment.getApplication()
  private var server = ServerSocket(0)
  private val requests = AtomicInteger()
  private val played = AtomicInteger()

  @After
  fun shutdown() {
    server.close()
  }

  @Test
  fun `a live stream's close surfaces once its audio has been read`() {
    serve(framesFor = { SECONDS_1_5 })

    val outcome = play(live = true, RetryPolicy.Infinite())

    assertTrue("not the close: ${outcome.error}", outcome.error is DataSourceException)
    assertEquals(SECONDS_1_5, played.get())
    // The retry loop reconnects, not media3.
    assertEquals(1, requests.get())
  }

  @Test
  fun `a live stream closed with its burst holds the close until the burst has been read`() {
    serve(framesFor = { SECONDS_2 }, end = End.CLOSE)

    val (heldWhileUnread, outcome) = playBurst()

    assertNull("surfaced with the burst unread", heldWhileUnread)
    assertTrue("not the close: ${outcome.error}", outcome.error is DataSourceException)
    assertEquals(SECONDS_2, played.get())
  }

  @Test
  fun `a player plays a burst shorter than its play buffer that arrives with its close`() {
    serve(framesFor = { SECONDS_2 }, end = End.CLOSE)
    val ended = AtomicBoolean()
    val renderer = WarmingRenderer(connectionEnded = ended::get)
    val player =
      ExoPlayer.Builder(context, { _, _, _, _, _ -> arrayOf<Renderer>(renderer) })
        .setMediaSourceFactory(
          MediaFactory(
            context,
            cache = null,
            RetryPolicy.Infinite(),
            onLoadFailure = { _, _ -> ended.set(true) },
            getRequestConfig = { _, _ -> null },
          )
        )
        .setLoadControl(
          DynamicLoadControl(
            BufferConfig(bufferForPlaybackMs = 2_500, bufferForPlaybackAfterRebufferMs = 2_500)
          )
        )
        .build()
    var ready = false
    var error: PlaybackException? = null
    player.addListener(
      object : MediaPlayer.Listener {
        override fun onPlaybackStateChanged(state: Int) {
          if (state == MediaPlayer.STATE_READY) ready = true
        }

        override fun onPlayerError(e: PlaybackException) {
          error = e
        }
      }
    )
    try {
      player.setMediaItem(
        TrackFactory.toMedia3(
          track(src = "http://127.0.0.1:${server.localPort}/stream.mp3").copy(live = true)
        )
      )
      player.prepare()
      player.play()
      val deadline = System.currentTimeMillis() + 10_000
      while (error == null) {
        if (System.currentTimeMillis() > deadline) fail("no error; ready: $ready")
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(1)
      }
    } finally {
      player.release()
    }

    assertTrue("never ready: ${error?.cause}", ready)
    assertEquals(SECONDS_2, renderer.read)
    assertTrue("not the close: ${error?.cause}", error?.cause is DataSourceException)
  }

  @Test
  fun `a stream of unknown length whose connection drops holds the drop until its audio has been read`() {
    serve(framesFor = { SECONDS_2 }, end = End.DROP)

    val (heldWhileUnread, outcome) = playBurst(live = false)

    assertNull("surfaced with the audio unread", heldWhileUnread)
    assertTrue("not the drop: ${outcome.error}", outcome.error is DataSourceException)
    assertEquals(SECONDS_2, played.get())
  }

  @Test
  fun `an on-demand file served without a length ends where the server ends it`() {
    serve(framesFor = { SECONDS_1_5 })

    val outcome = play(live = false, RetryPolicy.Infinite())

    assertNull(outcome.error)
    assertTrue("the file ended", outcome.ended)
    assertEquals(1, requests.get())
  }

  @Test
  fun `a live stream served with a length ends where its length ends`() {
    // ADTS, not MP3: the MP3 extractor stops at the end of a known length before the source ends.
    serve(
      framesFor = { SECONDS_1_5 },
      contentType = "audio/aac",
      withLength = true,
      body = ::silentAdts,
    )

    val outcome = play(live = true, RetryPolicy.Infinite())

    assertNull(outcome.error)
    assertTrue("the stream ended", outcome.ended)
    assertEquals(1, requests.get())
  }

  @Test
  fun `a live stream whose container declares its end ends there`() {
    serve(
      framesFor = { 0 },
      contentType = "audio/wav",
      body = { silentWavBytes(Duration.ofSeconds(1)) },
    )

    val outcome = play(live = true, RetryPolicy.Infinite())

    assertNull(outcome.error)
    assertTrue("the stream ended", outcome.ended)
    assertEquals(1, requests.get())
  }

  @Test
  fun `a live station answering with an empty body fails at once as unplayable`() =
    assertFailsAtOnce("audio/mpeg", "")

  @Test
  fun `a live station answering with a few bytes fails at once as unplayable`() =
    assertFailsAtOnce("audio/mpeg", "ICY 404")

  @Test
  fun `a live station answering with a playlist fails at once as unplayable`() {
    assertFailsAtOnce("audio/x-mpegurl", "#EXTM3U\nhttp://127.0.0.1:9/live.mp3\n")
    assertFailsAtOnce("audio/x-scpls", "[playlist]\nFile1=http://127.0.0.1:9/live.mp3\n")
    assertFailsAtOnce("application/pls+xml", "[playlist]\nFile1=http://127.0.0.1:9/live.mp3\n")
  }

  @Test
  fun `a live station answering with a page fails at once as unplayable`() {
    assertFailsAtOnce("text/html", "<html>Stream offline</html>")
    assertFailsAtOnce("application/octet-stream", "<html>Stream offline</html>")
    assertFailsAtOnce(null, "<html>Stream offline</html>")
  }

  /**
   * A live track whose one reply, [body] as [contentType], ends in the unrecognised format error.
   */
  private fun assertFailsAtOnce(contentType: String?, body: String) {
    requests.set(0)
    server.close()
    server = ServerSocket(0)
    serve(framesFor = { 0 }, contentType = contentType, body = { body.toByteArray() })

    val outcome = play(live = true, RetryPolicy.Infinite())

    assertTrue(
      "$contentType: not a parse error: ${outcome.error}",
      outcome.error is ParserException,
    )
    assertEquals(contentType, 1, requests.get())
  }

  /**
   * Answers the [n]th request, from 1, with the [body] of [framesFor]`(n)` frames as [contentType],
   * silence by default and [withLength] or not, then ends it cleanly once they have all been read:
   * a server sending in real time ends after its audio, never with it; see [End] for the others.
   */
  private fun serve(
    framesFor: (n: Int) -> Int,
    contentType: String? = "audio/mpeg",
    withLength: Boolean = false,
    end: End = End.AFTER_PLAYED,
    body: (frames: Int) -> ByteArray = ::silentMp3,
  ) {
    val server = server
    thread(isDaemon = true) {
      var sent = 0
      while (!server.isClosed) {
        val socket =
          try {
            server.accept()
          } catch (_: IOException) {
            return@thread
          }
        socket.use {
          val request = it.getInputStream().bufferedReader()
          while (!request.readLine().isNullOrEmpty()) Unit
          val frames = framesFor(requests.incrementAndGet())
          val bytes = body(frames)
          val headers = buildString {
            append("HTTP/1.1 200 OK\r\nConnection: close\r\n")
            contentType?.let { type -> append("Content-Type: $type\r\n") }
            if (withLength) append("Content-Length: ${bytes.size}\r\n")
            if (end == End.DROP) append("Transfer-Encoding: chunked\r\n")
            append("\r\n")
          }
          it.getOutputStream().run {
            write(headers.toByteArray())
            // A chunk with no last chunk after it: the body ends short.
            if (end == End.DROP) write("${bytes.size.toString(16)}\r\n".toByteArray())
            write(bytes)
            flush()
          }
          sent += frames
          while (end == End.AFTER_PLAYED && played.get() < sent && !server.isClosed) Thread.sleep(1)
        }
      }
    }
  }

  private enum class End {
    /** Cleanly, once the frames have been read. */
    AFTER_PLAYED,

    /** Cleanly, as soon as they are sent: a burst on connect, then a close. */
    CLOSE,

    /** As soon as they are sent, cut off within a chunked body: a dropped connection. */
    DROP,
  }

  private class Outcome(val ended: Boolean, val error: IOException?)

  /**
   * Plays a track as [play] does, but first waits for the connection to end with its audio unread
   * and asks the stream for its error, as ExoPlayer asks a renderer's stream while it buffers.
   * Answers that error, if any, and the outcome.
   */
  private fun playBurst(live: Boolean = true): Pair<IOException?, Outcome> {
    var heldWhileUnread: IOException? = null
    val outcome =
      play(live, RetryPolicy.Infinite()) { period, stream ->
        awaitConnectionEnd(period)
        try {
          // ExoPlayer asks the period until it has handled its preparation, which can come after
          // the connection's end, and then asks the stream.
          period.maybeThrowPrepareError()
          stream.maybeThrowError()
        } catch (e: IOException) {
          heldWhileUnread = e
        }
      }
    return heldWhileUnread to outcome
  }

  private fun awaitConnectionEnd(period: MediaPeriod) {
    val deadline = System.currentTimeMillis() + 5_000
    while (period.isLoading) {
      if (System.currentTimeMillis() > deadline) fail("the connection never ended")
      Thread.sleep(5)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }

  private fun play(
    live: Boolean,
    retryPolicy: RetryPolicy,
    beforeReading: (MediaPeriod, SampleStream) -> Unit = { _, _ -> },
  ): Outcome {
    val factory =
      MediaFactory(context, cache = null, retryPolicy, getRequestConfig = { _, _ -> null })
    val item =
      TrackFactory.toMedia3(
        track(src = "http://127.0.0.1:${server.localPort}/stream.mp3")
          .copy(live = live.takeIf { it })
      )
    val source = factory.createMediaSource(item)
    source.prepareSource(
      { _, _ -> },
      PlayerId.UNSET,
      DefaultBandwidthMeter.Builder(context).build(),
    )
    val period =
      source.createPeriod(
        MediaSource.MediaPeriodId(Any()),
        DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        0,
      )
    var prepared = false
    period.prepare(
      object : MediaPeriod.Callback {
        override fun onPrepared(mediaPeriod: MediaPeriod) {
          prepared = true
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
          source.continueLoading(LoadingInfo.Builder().build())
        }
      },
      0,
    )
    try {
      val prepareDeadline = System.currentTimeMillis() + 5_000
      while (!prepared) {
        if (System.currentTimeMillis() > prepareDeadline) fail("the stream never prepared")
        try {
          period.maybeThrowPrepareError()
        } catch (e: IOException) {
          return Outcome(ended = false, error = e)
        }
        Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
      }
      val streams = arrayOfNulls<SampleStream>(1)
      period.selectTracks(
        arrayOf<ExoTrackSelection>(FixedTrackSelection(period.trackGroups[0], 0)),
        booleanArrayOf(false),
        streams,
        booleanArrayOf(false),
        0,
      )
      val stream = checkNotNull(streams[0])
      beforeReading(period, stream)
      val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
      val deadline = System.currentTimeMillis() + 60_000
      while (true) {
        if (System.currentTimeMillis() > deadline)
          fail("still playing after ${played.get()} frames")
        buffer.clear()
        val result = stream.readData(FormatHolder(), buffer, SampleStream.FLAG_OMIT_SAMPLE_DATA)
        period.readDiscontinuity()
        when (result) {
          C.RESULT_BUFFER_READ -> {
            if (buffer.isEndOfStream) return Outcome(ended = true, error = null)
            played.incrementAndGet()
            ShadowSystemClock.advanceBy(Duration.ofNanos(FRAME_NS))
          }
          C.RESULT_NOTHING_READ -> {
            // As a renderer that has run out does: an error surfaces once the buffer is empty.
            try {
              stream.maybeThrowError()
            } catch (e: IOException) {
              return Outcome(ended = false, error = e)
            }
            period.continueLoading(LoadingInfo.Builder().build())
            Thread.sleep(1)
          }
        }
        shadowOf(Looper.getMainLooper()).idle()
      }
    } finally {
      source.releasePeriod(period)
      source.releaseSource { _, _ -> }
    }
  }

  /**
   * Reads samples once started, as a renderer playing them does, and is ready, with samples to
   * read, from its second render after [connectionEnded]: a MediaCodec renderer can still be
   * starting its codec when a burst's connection has already ended. From the first, ExoPlayer would
   * never see it not ready with the connection ended.
   */
  private class WarmingRenderer(private val connectionEnded: () -> Boolean) :
    BaseRenderer(C.TRACK_TYPE_AUDIO) {
    @Volatile var read = 0
    private var rendersSinceEnd = 0
    private var hasFormat = false
    private var ended = false
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)

    override fun getName() = "WarmingRenderer"

    override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_HANDLED)

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
      if (connectionEnded()) rendersSinceEnd++
      while (!hasFormat || state == STATE_STARTED) {
        buffer.clear()
        when (readSource(formatHolder, buffer, SampleStream.FLAG_OMIT_SAMPLE_DATA)) {
          C.RESULT_FORMAT_READ -> hasFormat = true
          C.RESULT_BUFFER_READ -> if (buffer.isEndOfStream) ended = true else read++
          else -> return
        }
      }
    }

    override fun isReady() = hasFormat && rendersSinceEnd >= 2 && isSourceReady

    override fun isEnded() = ended
  }

  private companion object {
    // MPEG-1 Layer III at 32 kbit/s, 44.1 kHz, mono: 104 bytes and 1152 samples a frame.
    val FRAME_HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x10, 0xC0.toByte())
    const val FRAME_BYTES = 104
    const val FRAME_NS = 1152 * 1_000_000_000L / 44_100
    const val SECONDS_1_5 = 57
    const val SECONDS_2 = 77
    const val SECONDS_60 = 2_297

    /** [frames] ADTS frames of AAC LC at 44.1 kHz, mono, with 100 zero bytes of payload each. */
    fun silentAdts(frames: Int): ByteArray {
      val length = 7 + 100
      val header =
        byteArrayOf(
          0xFF.toByte(),
          0xF1.toByte(),
          0x50,
          (0x40 or (length shr 11)).toByte(),
          (length shr 3).toByte(),
          ((length and 7) shl 5 or 0x1F).toByte(),
          0xFC.toByte(),
        )
      val frame = header + ByteArray(length - header.size)
      return ByteArray(frames * length) { frame[it % length] }
    }

    /** [frames] frames whose side info and main data are zero: silence any decoder accepts. */
    fun silentMp3(frames: Int): ByteArray {
      val frame = FRAME_HEADER + ByteArray(FRAME_BYTES - FRAME_HEADER.size)
      return ByteArray(frames * FRAME_BYTES) { frame[it % FRAME_BYTES] }
    }
  }
}
