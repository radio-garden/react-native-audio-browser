package com.audiobrowser.util

import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowseArtworkRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackFactoryRatingTest {
  @Before
  fun favoritesOn() {
    TrackFactory.favoriteEnabled = true
  }

  @Test
  fun `favorited true advertises a rated, hearted userRating`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = true))
    val rating = item.mediaMetadata.userRating as HeartRating
    assertTrue(rating.isRated)
    assertTrue(rating.isHeart)
  }

  @Test
  fun `favorited false advertises a rated, un-hearted userRating`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = false))
    val rating = item.mediaMetadata.userRating as HeartRating
    assertTrue(rating.isRated)
    assertEquals(false, rating.isHeart)
  }

  @Test
  fun `favorited null advertises an unrated heart, so a car still draws its rating control`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = null))
    val rating = item.mediaMetadata.userRating as HeartRating
    assertFalse(rating.isRated)
  }

  @Test
  fun `a browsable item with no known favorite carries no rating`() {
    val item = TrackFactory.toMedia3(TestFixtures.browseTrack())
    assertNull(item.mediaMetadata.userRating)
  }

  @Test
  fun `favoriting off advertises no userRating`() {
    TrackFactory.favoriteEnabled = false
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = true))
    assertNull(item.mediaMetadata.userRating)
  }

  @After
  fun resetDevice() {
    Automotive.device = false
    TrackFactory.artworkRoutes = null
    TrackFactory.favoriteEnabled = false
  }

  private fun automotive(): BrowseArtworkRegistry =
    BrowseArtworkRegistry().also {
      Automotive.device = true
      TrackFactory.artworkRoutes = ArtworkRoutes(it, "app.artwork")
    }

  @Test
  fun `Android Automotive OS serves http artwork through the provider`() {
    val registry = automotive()
    val item = TrackFactory.toMedia3(TestFixtures.track(artwork = "https://a/1.png"))
    val uri = item.mediaMetadata.artworkUri!!
    assertEquals("content", uri.scheme)
    assertEquals("app.artwork", uri.authority)
    assertEquals("https://a/1.png", registry.lookup(ArtworkUris.parseToken(uri)!!)!!.finalUrl)
  }

  @Test
  fun `a phone keeps the http artwork`() {
    TrackFactory.artworkRoutes = ArtworkRoutes(BrowseArtworkRegistry(), "app.artwork")
    val item = TrackFactory.toMedia3(TestFixtures.track(artwork = "https://a/1.png"))
    assertEquals("https://a/1.png", item.mediaMetadata.artworkUri.toString())
  }

  @Test
  fun `Android Automotive OS keeps non-http artwork as it is`() {
    automotive()
    assertEquals(
      "android.resource://app/1",
      TrackFactory.sessionArtworkUri("android.resource://app/1", null, isSvg = false),
    )
  }

  @Test
  fun `the session's artwork outlives a clear of the registry, and maps back to its URL`() {
    val registry = automotive()
    val uri = TrackFactory.sessionArtworkUri("https://a/1.png", null, isSvg = false).toUri()
    registry.clear()
    assertEquals("https://a/1.png", TrackFactory.artworkRoutes!!.publicUrl(uri))
  }

  @Test
  fun `JS gets another URI as it is, and none for artwork the provider forgot`() {
    automotive()
    val routes = TrackFactory.artworkRoutes!!
    assertEquals("https://a/1.png", routes.publicUrl("https://a/1.png".toUri()))
    assertEquals("content://other/art/x", routes.publicUrl("content://other/art/x".toUri()))
    assertNull(routes.publicUrl("content://app.artwork/art/forgotten".toUri()))
  }

  @Test
  fun `the session pins a browse item's provider URI it is handed back`() {
    val registry = automotive()
    val browseUri =
      ArtworkRoutes(registry, "app.artwork").route("https://a/1.png", null, false, pinned = false)
    assertEquals(browseUri, TrackFactory.sessionArtworkUri(browseUri, null, isSvg = false))
    registry.clear()
    assertEquals("https://a/1.png", TrackFactory.artworkRoutes!!.publicUrl(browseUri.toUri()))
  }
}
