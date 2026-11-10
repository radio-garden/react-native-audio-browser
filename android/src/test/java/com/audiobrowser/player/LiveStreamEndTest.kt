package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
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
import com.audiobrowser.util.TrackFactory
import java.io.IOException
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * A progressive stream that the server ends cleanly, served without a length, loaded through
 * [MediaFactory] by media3's own media period. The test reads the samples as a renderer would and
 * advances the clock by the audio it reads, since Robolectric plays no audio.
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
  fun `a live stream that closes before it has played reconnects`() {
    serve(framesFor = { request -> if (request == 1) SECONDS_1_5 else SECONDS_60 })

    val outcome =
      play(
        live = true,
        RetryPolicy.Infinite(),
        stopAfterFrames = SECONDS_1_5 * 3,
        hasPlayed = false,
      )

    assertNull(outcome.error)
    assertEquals(2, requests.get())
  }

  @Test
  fun `a live stream that closes once reconnects once and plays on`() {
    serve(framesFor = { request -> if (request == 1) SECONDS_1_5 else SECONDS_60 })

    val outcome = play(live = true, RetryPolicy.Infinite(), stopAfterFrames = SECONDS_1_5 * 3)

    assertNull(outcome.error)
    assertTrue("the stream played on", !outcome.ended)
    assertEquals(2, requests.get())
  }

  @Test
  fun `a live stream that closes after every second and a half ends in an error`() {
    serve(framesFor = { SECONDS_1_5 })

    val outcome = play(live = true, RetryPolicy.Infinite())

    assertNotNull("the retries never ran out", outcome.error)
    // The 120 s recovery budget starts at the first close, at 1.49 s: closes come every 1.49 s,
    // and the first at or past 120 s after it, the 82nd, is not retried.
    assertEquals(82, requests.get())
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
    serve(framesFor = { 0 }, contentType = "audio/wav", body = { silentWav() })

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
   * a server sending in real time ends after its audio, never with it.
   */
  private fun serve(
    framesFor: (n: Int) -> Int,
    contentType: String? = "audio/mpeg",
    withLength: Boolean = false,
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
            append("\r\n")
          }
          it.getOutputStream().run {
            write(headers.toByteArray())
            write(bytes)
            flush()
          }
          sent += frames
          while (played.get() < sent && !server.isClosed) Thread.sleep(1)
        }
      }
    }
  }

  private class Outcome(val ended: Boolean, val error: IOException?)

  private fun play(
    live: Boolean,
    retryPolicy: RetryPolicy,
    stopAfterFrames: Int = Int.MAX_VALUE,
    hasPlayed: Boolean = true,
  ): Outcome {
    val factory =
      MediaFactory(
        context,
        cache = null,
        retryPolicy,
        hasPlayed = { hasPlayed },
        getRequestConfig = { _, _ -> null },
      )
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
      val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
      val deadline = System.currentTimeMillis() + 60_000
      while (played.get() < stopAfterFrames) {
        if (System.currentTimeMillis() > deadline)
          fail("still playing after ${played.get()} frames")
        try {
          stream.maybeThrowError()
        } catch (e: IOException) {
          return Outcome(ended = false, error = e)
        }
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
            period.continueLoading(LoadingInfo.Builder().build())
            Thread.sleep(1)
          }
        }
        shadowOf(Looper.getMainLooper()).idle()
      }
      return Outcome(ended = false, error = null)
    } finally {
      source.releasePeriod(period)
      source.releaseSource { _, _ -> }
    }
  }

  private companion object {
    // MPEG-1 Layer III at 32 kbit/s, 44.1 kHz, mono: 104 bytes and 1152 samples a frame.
    val FRAME_HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x10, 0xC0.toByte())
    const val FRAME_BYTES = 104
    const val FRAME_NS = 1152 * 1_000_000_000L / 44_100
    const val SECONDS_1_5 = 57
    const val SECONDS_60 = 2_297

    /** A WAV of a second of 8 kHz mono 8-bit silence, its data size in its header. */
    fun silentWav(): ByteArray {
      val samples = 8_000
      val header =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
          put("RIFF".toByteArray())
          putInt(36 + samples)
          put("WAVEfmt ".toByteArray())
          putInt(16)
          putShort(1)
          putShort(1)
          putInt(8_000)
          putInt(8_000)
          putShort(1)
          putShort(8)
          put("data".toByteArray())
          putInt(samples)
        }
      return header.array() + ByteArray(samples) { 0x80.toByte() }
    }

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
