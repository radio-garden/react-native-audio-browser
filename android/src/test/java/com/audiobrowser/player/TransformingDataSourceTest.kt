package com.audiobrowser.player

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransformingDataSourceTest {

  // Records the URL each upstream DataSource is actually opened with.
  private val openedUrls = mutableListOf<String>()

  // When set, the next upstream open() records its URL and then throws (simulating a failed load).
  private var failNextOpen = false

  private inner class RecordingDataSource(
    private val responseHeaders: Map<String, List<String>> = emptyMap()
  ) : DataSource {
    override fun open(dataSpec: DataSpec): Long {
      openedUrls.add(dataSpec.uri.toString())
      if (failNextOpen) {
        failNextOpen = false
        throw IOException("simulated load failure")
      }
      return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun getUri(): Uri? = null

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {}
  }

  private val upstreamFactory = DataSource.Factory { RecordingDataSource() }

  private fun config(baseUrl: String, path: String) =
    MediaRequestConfig(
      resolve = null,
      resolveSync = null,
      transform = null,
      transformSync = null,
      method = null,
      path = path,
      baseUrl = baseUrl,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )

  private fun tokenConfig(token: Int) =
    MediaRequestConfig(
      resolve = null,
      resolveSync = null,
      transform = null,
      transformSync = null,
      method = null,
      path = "/s.mp3",
      baseUrl = "https://cdn.example.com",
      headers = null,
      query = mapOf("token" to token.toString()),
      body = null,
      contentType = null,
      userAgent = null,
    )

  @Test
  fun `a failed media-url load re-resolves the url on the retry (fresh token)`() {
    // A consumer whose transform mints a short-lived token: each resolve yields a new token. If the
    // media-URL load fails (e.g. the token expired during a network outage), ExoPlayer's retry must
    // re-resolve to a fresh token rather than replay the stale cached URL.
    var resolves = 0
    val factory = TransformingDataSource.Factory(upstreamFactory) { tokenConfig(++resolves) }
    val spec = DataSpec("/listen/abc/channel.mp3".toUri())

    // First open records token=1, then fails (simulating an expired URL).
    failNextOpen = true
    try {
      factory.createDataSource().open(spec)
    } catch (_: IOException) {}
    // The retry re-opens the same media URL — it must re-resolve to a fresh token.
    factory.createDataSource().open(spec)
    // A further re-open without a failure reuses token=2 (the flag is single-shot).
    factory.createDataSource().open(spec)

    assertEquals(3, openedUrls.size)
    assertTrue("first open uses token=1, got ${openedUrls[0]}", openedUrls[0].contains("token=1"))
    assertTrue(
      "retry re-resolves to token=2, got ${openedUrls[1]}",
      openedUrls[1].contains("token=2"),
    )
    assertTrue(
      "subsequent open reuses token=2, got ${openedUrls[2]}",
      openedUrls[2].contains("token=2"),
    )
  }

  @Test
  fun `a failed segment load does not trigger a media re-resolve`() {
    // A segment/key failure (a different, already-absolute URL) must not refresh the media URL: the
    // media URL is fine, only a segment hiccuped.
    var resolves = 0
    val factory = TransformingDataSource.Factory(upstreamFactory) { tokenConfig(++resolves) }

    val mediaUrl = "/listen/abc/playlist.m3u8".toUri()
    factory.createDataSource().open(DataSpec(mediaUrl)) // media -> token=1
    failNextOpen = true
    try {
      factory.createDataSource().open(DataSpec("https://cdn.example.com/seg-1.ts".toUri()))
    } catch (_: IOException) {}
    // Re-open the media URL: it must still reuse token=1 (the segment failure left it valid).
    factory.createDataSource().open(DataSpec(mediaUrl))

    assertTrue(
      "media url stays token=1, got ${openedUrls.last()}",
      openedUrls.last().contains("token=1"),
    )
  }

  @Test
  fun `re-open of the media url reuses the resolved url, not the relative original`() {
    // radio.garden's MediaItem URI is a relative path; the transform resolves it to an absolute
    // URL.
    val relative = "/listen/abc/channel.mp3"
    val factory =
      TransformingDataSource.Factory(upstreamFactory) {
        config("https://cdn.example.com", "/s.mp3")
      }
    val spec = DataSpec(relative.toUri())

    // First open resolves + caches; a re-open (e.g. a retry after a network drop) opens again.
    factory.createDataSource().open(spec)
    factory.createDataSource().open(spec)

    assertEquals(2, openedUrls.size)
    // The regression: the re-open must NOT fall back to the schemeless relative path (which would
    // route to FileDataSource -> FileNotFoundException). Both opens hit the resolved absolute URL.
    assertTrue(
      "expected absolute urls, got $openedUrls",
      openedUrls.all { it.startsWith("https://") },
    )
    assertEquals(openedUrls[0], openedUrls[1])
  }

  @Test
  fun `response headers are forwarded from the upstream source`() {
    // ICY (Shoutcast) song metadata only works if ExoPlayer's ProgressiveMediaPeriod can read the
    // `icy-metaint` response header back through the data-source chain. A transparent wrapper that
    // swallows getResponseHeaders() (returning the DataSource default empty map) silently disables
    // ICY metadata — playback still works, but onMetadata never fires.
    val icyHeaders = mapOf("icy-metaint" to listOf("16000"), "icy-name" to listOf("Example FM"))
    val factory =
      TransformingDataSource.Factory({ RecordingDataSource(icyHeaders) }) {
        config("https://cdn.example.com", "/s.mp3")
      }
    val source = factory.createDataSource()
    source.open(DataSpec("/listen/abc/channel.mp3".toUri()))

    assertEquals(icyHeaders, source.responseHeaders)
  }

  @Test
  fun `absolute segment urls pass through unchanged after the media url is resolved`() {
    val factory =
      TransformingDataSource.Factory(upstreamFactory) {
        config("https://cdn.example.com", "/playlist.m3u8")
      }

    // Resolve the (relative) media/playlist URL first…
    factory.createDataSource().open(DataSpec("/listen/abc/playlist.m3u8".toUri()))
    // …then an already-absolute segment URL must pass through untouched (only headers are reused).
    val segment = "https://cdn.example.com/segment-1.ts"
    factory.createDataSource().open(DataSpec(segment.toUri()))

    assertEquals(segment, openedUrls.last())
  }
}
