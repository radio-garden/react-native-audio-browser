package com.audiobrowser.destination.sonos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Deterministic DIDL-Lite metadata for `SetAVTransportURI`. */
class DidlLiteTest {

  private val header =
    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"

  @Test
  fun `builds a minimal live audioBroadcast item`() {
    val didl = DidlLite.build(title = "Radio Swiss Jazz", live = true)
    assertEquals(
      header +
        "<item id=\"0\" parentID=\"-1\" restricted=\"true\">" +
        "<dc:title>Radio Swiss Jazz</dc:title>" +
        "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
        "</item></DIDL-Lite>",
      didl,
    )
  }

  @Test
  fun `includes creator, album and artwork in a fixed order when present`() {
    val didl =
      DidlLite.build(
        title = "Now Playing",
        creator = "The Artist",
        album = "The Album",
        albumArtUri = "http://art/cover.jpg",
        live = true,
      )
    assertEquals(
      header +
        "<item id=\"0\" parentID=\"-1\" restricted=\"true\">" +
        "<dc:title>Now Playing</dc:title>" +
        "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
        "<dc:creator>The Artist</dc:creator>" +
        "<upnp:album>The Album</upnp:album>" +
        "<upnp:albumArtURI>http://art/cover.jpg</upnp:albumArtURI>" +
        "</item></DIDL-Lite>",
      didl,
    )
  }

  @Test
  fun `uses the music-track class when not live`() {
    val didl = DidlLite.build(title = "A Song", live = false)
    assertEquals(
      header +
        "<item id=\"0\" parentID=\"-1\" restricted=\"true\">" +
        "<dc:title>A Song</dc:title>" +
        "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
        "</item></DIDL-Lite>",
      didl,
    )
  }

  @Test
  fun `escapes XML special characters in text fields`() {
    val didl = DidlLite.build(title = "Rock & Roll <\"Live\">", creator = "A & B", live = true)
    assertEquals(
      header +
        "<item id=\"0\" parentID=\"-1\" restricted=\"true\">" +
        "<dc:title>Rock &amp; Roll &lt;&quot;Live&quot;&gt;</dc:title>" +
        "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
        "<dc:creator>A &amp; B</dc:creator>" +
        "</item></DIDL-Lite>",
      didl,
    )
  }

  @Test
  fun `does not emit empty optional fields`() {
    val didl = DidlLite.build(title = "T", creator = "", album = null, albumArtUri = "  ", live = true)
    assertEquals(
      header +
        "<item id=\"0\" parentID=\"-1\" restricted=\"true\">" +
        "<dc:title>T</dc:title>" +
        "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
        "</item></DIDL-Lite>",
      didl,
    )
  }
}
