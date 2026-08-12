package com.audiobrowser.util

import android.net.Uri
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.margelo.nitro.audiobrowser.ImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackFactoryBrowseTest {
  private val authority = ArtworkUris.authorityFor("com.test")

  @Test
  fun `http artwork is wrapped in a content uri and registered`() {
    val reg = BrowseArtworkRegistry()
    val track =
      TestFixtures.browseTrack(
        artworkSource =
          ImageSource(
            uri = "https://cdn/a.svg",
            method = null,
            headers = mapOf("Authorization" to "Bearer t"),
            body = null,
          )
      )
    val item = TrackFactory.toBrowseMediaItem(track, reg, authority)

    val artUri = Uri.parse(item.mediaMetadata.artworkUri.toString())
    assertEquals("content", artUri.scheme)
    val token = ArtworkUris.parseToken(artUri)
    assertNotNull(token)
    val entry = reg.lookup(token!!)!!
    assertEquals("https://cdn/a.svg", entry.finalUrl)
    assertEquals("Bearer t", entry.headers!!["Authorization"])
    assertEquals(true, entry.isSvg) // detected from the .svg url
  }

  @Test
  fun `android resource artwork is passed through unchanged`() {
    val reg = BrowseArtworkRegistry()
    val resUri = "android.resource://com.test/drawable/ic_folder"
    val track = TestFixtures.browseTrack(artwork = resUri, artworkSource = null)
    val item = TrackFactory.toBrowseMediaItem(track, reg, authority)
    assertEquals(resUri, item.mediaMetadata.artworkUri.toString())
  }
}
