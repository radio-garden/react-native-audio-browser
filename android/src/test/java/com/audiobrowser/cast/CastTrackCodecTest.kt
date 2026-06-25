package com.audiobrowser.cast

import com.margelo.nitro.audiobrowser.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trips the Cast `customData` Track identity through [CastTrackCodec]. Robolectric supplies a
 * real `org.json` (the JVM stub returns defaults). Verifies the identity fields survive, `live`
 * round-trips (true / false / absent), and unset fields decode back to null.
 */
@RunWith(RobolectricTestRunner::class)
class CastTrackCodecTest {

  private fun track(
    id: String? = null,
    url: String? = null,
    src: String? = null,
    title: String = "",
    subtitle: String? = null,
    artist: String? = null,
    album: String? = null,
    artwork: String? = null,
    live: Boolean? = null,
  ): Track =
    CastTrackCodec.blankTrack()
      .copy(
        id = id,
        url = url,
        src = src,
        title = title,
        subtitle = subtitle,
        artist = artist,
        album = album,
        artwork = artwork,
        live = live,
      )

  @Test
  fun `full identity round-trips`() {
    val original =
      track(
        id = "id-1",
        url = "/stations/foo",
        src = "https://cdn.example.com/foo.m3u8",
        title = "Foo FM",
        subtitle = "Berlin",
        artist = "Foo Artist",
        album = "Foo Album",
        artwork = "https://cdn.example.com/foo.png",
        live = true,
      )

    val decoded = CastTrackCodec.fromJson(CastTrackCodec.toJson(original))

    assertEquals("id-1", decoded.id)
    assertEquals("/stations/foo", decoded.url)
    assertEquals("https://cdn.example.com/foo.m3u8", decoded.src)
    assertEquals("Foo FM", decoded.title)
    assertEquals("Berlin", decoded.subtitle)
    assertEquals("Foo Artist", decoded.artist)
    assertEquals("Foo Album", decoded.album)
    assertEquals("https://cdn.example.com/foo.png", decoded.artwork)
    assertEquals(true, decoded.live)
  }

  @Test
  fun `null and blank fields decode back to null`() {
    val original = track(src = "https://cdn.example.com/bar.mp3", title = "Bar")

    val decoded = CastTrackCodec.fromJson(CastTrackCodec.toJson(original))

    assertNull(decoded.id)
    assertNull(decoded.url)
    assertNull(decoded.subtitle)
    assertNull(decoded.artist)
    assertNull(decoded.album)
    assertNull(decoded.artwork)
    assertNull(decoded.live)
    assertEquals("https://cdn.example.com/bar.mp3", decoded.src)
    assertEquals("Bar", decoded.title)
  }

  @Test
  fun `live false round-trips as false, absent stays null`() {
    val notLive = CastTrackCodec.fromJson(CastTrackCodec.toJson(track(title = "x", live = false)))
    assertEquals(false, notLive.live)

    val unknownLive = CastTrackCodec.fromJson(CastTrackCodec.toJson(track(title = "x", live = null)))
    assertNull(unknownLive.live)
  }

  @Test
  fun `toJson omits unset fields but always includes title`() {
    val json = CastTrackCodec.toJson(track(src = "s", title = "T"))
    assertTrue(json.has("src"))
    assertTrue(json.has("title"))
    assertFalse(json.has("id"))
    assertFalse(json.has("subtitle"))
    assertFalse(json.has("live"))
  }

  @Test
  fun `fromCustomData reads the keyed envelope`() {
    val envelope =
      JSONObject().apply {
        put(CastTrackCodec.KEY_TRACK, CastTrackCodec.toJson(track(src = "s", title = "T")))
      }
    val decoded = CastTrackCodec.fromCustomData(envelope)
    assertEquals("s", decoded?.src)
    assertEquals("T", decoded?.title)
  }

  @Test
  fun `fromCustomData returns null for null or unkeyed data`() {
    assertNull(CastTrackCodec.fromCustomData(null))
    assertNull(CastTrackCodec.fromCustomData(JSONObject()))
  }
}
