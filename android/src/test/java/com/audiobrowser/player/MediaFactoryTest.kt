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
import com.margelo.nitro.audiobrowser.Track
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MediaFactoryTest {

  @Test
  fun `a queued track is handed to the media request config when its media url opens`() {
    val context = RuntimeEnvironment.getApplication()
    val requests = LinkedBlockingQueue<Pair<String, Track>>()
    val factory =
      MediaFactory(
        context,
        cache = null,
        RetryPolicy.Default,
        shouldRetry = { false },
        getRequestConfig = { url, track ->
          requests.add(url to track)
          null
        },
      )
    val queued = track(id = "queued", src = "http://127.0.0.1:9/queued.mp3")
    val source = factory.createMediaSource(TrackFactory.toMedia3(queued))
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

      val (url, track) =
        checkNotNull(requests.poll(5, TimeUnit.SECONDS)) { "the media url never opened" }
      assertEquals("http://127.0.0.1:9/queued.mp3", url)
      assertEquals(queued, track)
    } finally {
      source.releasePeriod(period)
      source.releaseSource { _, _ -> }
    }
  }
}
