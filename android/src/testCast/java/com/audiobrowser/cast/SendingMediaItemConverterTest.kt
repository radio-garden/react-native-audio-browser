package com.audiobrowser.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiobrowser.remote.isRemoteLive
import com.audiobrowser.remote.remoteItemOf
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SendingMediaItemConverterTest {
  private val converter = SendingMediaItemConverter()

  private fun item(id: String, live: Boolean) =
    remoteItemOf(
      MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Item $id").build())
        .build(),
      "https://cdn.example/$id",
      null,
      live,
    )

  @Test
  fun `each send of an item has a content id of its own`() {
    val station = item("a", live = true)

    val first = converter.toMediaQueueItem(station).media!!.contentId
    val second = converter.toMediaQueueItem(station).media!!.contentId

    assertNotEquals(first, second)
    assertTrue(first.startsWith("a"))
    assertTrue(second.startsWith("a"))
  }

  @Test
  fun `an item comes back with its own media id and URL`() {
    val track = item("t1", live = false)

    val back = converter.toMediaItem(converter.toMediaQueueItem(track))

    assertEquals("t1", back.mediaId)
    assertEquals("https://cdn.example/t1", back.localConfiguration?.uri.toString())
  }

  @Test
  fun `a live item goes as a live stream and comes back live`() {
    val station = item("a", live = true)

    val sent = converter.toMediaQueueItem(station)

    assertEquals(MediaInfo.STREAM_TYPE_LIVE, sent.media!!.streamType)
    // The URL has no extension, so the type falls back to audio/mpeg.
    assertEquals("audio/mpeg", sent.media!!.contentType)
    assertTrue(converter.toMediaItem(sent).isRemoteLive)
  }

  @Test
  fun `a track doesn't go as a live stream`() {
    val sent = converter.toMediaQueueItem(item("t1", live = false))

    assertNotEquals(MediaInfo.STREAM_TYPE_LIVE, sent.media!!.streamType)
  }

  @Test
  fun `an item the receiver reports without its media is empty`() {
    val unloaded = mock(MediaQueueItem::class.java)

    assertSame(MediaItem.EMPTY, converter.toMediaItem(unloaded))
  }
}
