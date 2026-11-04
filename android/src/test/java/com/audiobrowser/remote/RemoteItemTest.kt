package com.audiobrowser.remote

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteItemTest {
  private val item =
    MediaItem.Builder()
      .setMediaId("a")
      .setUri("https://local/a")
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("A")
          .setArtworkUri(android.net.Uri.parse("content://art/a"))
          .build()
      )
      .build()

  @Test
  fun `a live item loads at its live edge as a radio station`() {
    val remote = remoteItemOf(item, "https://cdn/a", null, live = true)

    assertTrue(remote.isRemoteLive)
    assertEquals(MediaMetadata.MEDIA_TYPE_RADIO_STATION, remote.mediaMetadata.mediaType)
    assertEquals("https://cdn/a", remote.localConfiguration?.uri.toString())
    assertEquals("a", remote.mediaId)
  }

  @Test
  fun `a track is not live`() {
    assertFalse(remoteItemOf(item, "https://cdn/a.mp3", null, live = false).isRemoteLive)
  }

  @Test
  fun `artwork the receiver can't fetch is left out`() {
    assertNull(remoteItemOf(item, "https://cdn/a", null, live = false).mediaMetadata.artworkUri)
    assertEquals(
      "https://img/a.jpg",
      remoteItemOf(item, "https://cdn/a", "https://img/a.jpg", live = false)
        .mediaMetadata
        .artworkUri
        .toString(),
    )
  }

  @Test
  fun `the MIME type is the declared one, else inferred, else MPEG audio`() {
    assertEquals("audio/aac", remoteMimeType("https://cdn/a.mp3", "audio/aac"))
    assertEquals(MimeTypes.APPLICATION_M3U8, remoteMimeType("https://cdn/a.m3u8", null))
    assertEquals(MimeTypes.AUDIO_MPEG, remoteMimeType("https://cdn/stream", null))
  }
}
