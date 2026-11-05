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

  private val openedUrls = mutableListOf<String>()

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
    var resolves = 0
    val factory = TransformingDataSource.Factory(upstreamFactory) { tokenConfig(++resolves) }
    val spec = DataSpec("/listen/abc/channel.mp3".toUri())

    failNextOpen = true
    try {
      factory.createDataSource().open(spec)
    } catch (_: IOException) {}
    factory.createDataSource().open(spec)
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
    var resolves = 0
    val factory = TransformingDataSource.Factory(upstreamFactory) { tokenConfig(++resolves) }

    val mediaUrl = "/listen/abc/playlist.m3u8".toUri()
    factory.createDataSource().open(DataSpec(mediaUrl))
    failNextOpen = true
    try {
      factory.createDataSource().open(DataSpec("https://cdn.example.com/seg-1.ts".toUri()))
    } catch (_: IOException) {}
    factory.createDataSource().open(DataSpec(mediaUrl))

    assertTrue(
      "media url stays token=1, got ${openedUrls.last()}",
      openedUrls.last().contains("token=1"),
    )
  }

  @Test
  fun `re-open of the media url reuses the resolved url, not the relative original`() {
    val relative = "/listen/abc/channel.mp3"
    val factory =
      TransformingDataSource.Factory(upstreamFactory) {
        config("https://cdn.example.com", "/s.mp3")
      }
    val spec = DataSpec(relative.toUri())

    factory.createDataSource().open(spec)
    factory.createDataSource().open(spec)

    assertEquals(2, openedUrls.size)
    // A schemeless URL would reach the file data source and fail.
    assertTrue(
      "expected absolute urls, got $openedUrls",
      openedUrls.all { it.startsWith("https://") },
    )
    assertEquals(openedUrls[0], openedUrls[1])
  }

  @Test
  fun `response headers are forwarded from the upstream source`() {
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

    factory.createDataSource().open(DataSpec("/listen/abc/playlist.m3u8".toUri()))
    val segment = "https://cdn.example.com/segment-1.ts"
    factory.createDataSource().open(DataSpec(segment.toUri()))

    assertEquals(segment, openedUrls.last())
  }
}
