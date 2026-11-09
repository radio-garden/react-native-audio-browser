package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.Track
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MediaFactoryTest {

  private val context = RuntimeEnvironment.getApplication()

  private fun mediaFactory(
    getRequestConfig: (String, Track) -> MediaRequestConfig? = { _, _ -> null }
  ) =
    MediaFactory(
      context,
      cache = null,
      RetryPolicy.Default,
      getRequestConfig = getRequestConfig,
    )

  /** Starts loading [track]'s media from position 0, runs [block], then releases the source. */
  private fun loading(factory: MediaFactory, track: Track, block: () -> Unit) {
    val source = factory.createMediaSource(TrackFactory.toMedia3(track))
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
    try {
      period.prepare(
        object : MediaPeriod.Callback {
          override fun onPrepared(mediaPeriod: MediaPeriod) {}

          override fun onContinueLoadingRequested(source: MediaPeriod) {}
        },
        0,
      )
      block()
    } finally {
      source.releasePeriod(period)
      source.releaseSource { _, _ -> }
    }
  }

  @Test
  fun `a queued track is handed to the media request config when its media url opens`() {
    val requests = LinkedBlockingQueue<Pair<String, Track>>()
    val factory = mediaFactory { url, track ->
      requests.add(url to track)
      null
    }
    val queued = track(id = "queued", src = "http://127.0.0.1:9/queued.mp3")

    loading(factory, queued) {
      val (url, track) =
        checkNotNull(requests.poll(5, TimeUnit.SECONDS)) { "the media url never opened" }
      assertEquals("http://127.0.0.1:9/queued.mp3", url)
      assertEquals(queued, track)
    }
  }

  @Test
  fun `a read from the start asks for an open-ended range, past a cross-origin redirect`() {
    MockWebServer().use { stream ->
      MockWebServer().use { origin ->
        stream.enqueue(MockResponse().setBody("").setHeader("Content-Type", "audio/mpeg"))
        origin.enqueue(
          MockResponse().setResponseCode(302).setHeader("Location", stream.url("/live.mp3"))
        )

        loading(mediaFactory(), track(id = "live", src = origin.url("/listen.mp3").toString())) {
          val first =
            checkNotNull(origin.takeRequest(5, TimeUnit.SECONDS)) { "the media url never opened" }
          assertEquals("bytes=0-", first.getHeader("Range"))
          val request =
            checkNotNull(stream.takeRequest(5, TimeUnit.SECONDS)) {
              "the redirect was not followed"
            }
          assertEquals("bytes=0-", request.getHeader("Range"))
        }
      }
    }
  }
}
