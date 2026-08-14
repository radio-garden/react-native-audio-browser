package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.StyleDisplay
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Style blocks on the wire, decoded tolerantly (ADR 0011). */
class JsonStyleTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `decodes a block onto TrackStyle`() {
    val track =
      json.decodeFromString<JsonTrack>(
        """{"title":"X","style":{"display":"grid","artworkRendering":"stencil"}}"""
      )
    val style = track.toNitro().style
    assertEquals(StyleDisplay.GRID, style?.display)
    assertEquals(ArtworkRendering.STENCIL, style?.artworkRendering)
  }

  @Test
  fun `decodes a block onto SectionStyle`() {
    val resolved =
      json.decodeFromString<JsonResolvedTrack>(
        """{"path":"/home","title":"Home","sections":[
          {"style":{"display":"grid","gridWrap":false},"children":[{"title":"C","src":"s"}]}
        ]}"""
      )
    val style = resolved.toNitro().sections?.first()?.style
    assertEquals(StyleDisplay.GRID, style?.display)
    assertEquals(false, style?.gridWrap)
  }

  @Test
  fun `a stale string style decodes as no declaration - never a parse failure`() {
    // The retired string vocabulary must never kill the page (tolerant
    // decoding, ADR 0011).
    val resolved =
      json.decodeFromString<JsonResolvedTrack>(
        """{"path":"/home","title":"Home","style":"rail","children":[
          {"title":"C","src":"s","style":"grid"}
        ]}"""
      )
    val nitro = resolved.toNitro()
    assertNull(nitro.style)
    assertNull(nitro.children?.first()?.style)
  }

  @Test
  fun `unknown enum values decode as no declaration`() {
    // All-unknown declarations collapse to "no block" — one shape for "no
    // declaration" on every platform.
    val track =
      json.decodeFromString<JsonTrack>(
        """{"title":"X","style":{"display":"carousel","artworkRendering":"embossed","gridWrap":"yes"}}"""
      )
    assertNull(track.toNitro().style)
  }

  @Test
  fun `an empty block decodes as no declaration`() {
    val track = json.decodeFromString<JsonTrack>("""{"title":"X","style":{}}""")
    assertNull(track.toNitro().style)
  }

  @Test
  fun `a quoted boolean gridWrap is a wrong-typed field - no declaration`() {
    // iOS's strict-typed tolerant decode drops a quoted "false"; the lenient
    // Kotlin mapper must not read it either, or the same payload renders a
    // teaser shelf on one platform and a wrapping grid on the other.
    val resolved =
      json.decodeFromString<JsonResolvedTrack>(
        """{"path":"/home","title":"Home","sections":[
          {"style":{"display":"grid","gridWrap":"false"},"children":[{"title":"C","src":"s"}]}
        ]}"""
      )
    val style = resolved.toNitro().sections?.first()?.style
    assertEquals(StyleDisplay.GRID, style?.display)
    assertNull(style?.gridWrap)
  }

  @Test
  fun `decodes disabled`() {
    val track = json.decodeFromString<JsonTrack>("""{"title":"X","src":"s","disabled":true}""")
    assertEquals(true, track.toNitro().disabled)
  }
}
