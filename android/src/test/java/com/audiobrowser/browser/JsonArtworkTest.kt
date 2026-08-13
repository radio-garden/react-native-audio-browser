package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.Variant_String_ArtworkVariants
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonArtworkTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `decodes a single url`() {
    val track =
      json.decodeFromString<JsonTrack>("""{"title":"X","artwork":"https://e.example/a.png"}""")
    assertEquals(
      Variant_String_ArtworkVariants.First("https://e.example/a.png"),
      track.toNitro().artwork,
    )
  }

  @Test
  fun `decodes a per-appearance pair and takes dark`() {
    val track =
      json.decodeFromString<JsonTrack>(
        """{"title":"X","artwork":{"light":"https://e.example/l.png","dark":"https://e.example/d.png"}}"""
      )
    val artwork = track.toNitro().artwork
    assertEquals("https://e.example/d.png", artwork?.asSecondOrNull()?.dark)
    assertEquals("https://e.example/l.png", artwork?.asSecondOrNull()?.light)
  }

  @Test
  fun `decodes a pair nested in children`() {
    // The shape that shipped broken: a container decodes its children eagerly,
    // so one unparseable row rejected the whole page rather than losing an image.
    val resolved =
      json.decodeFromString<JsonResolvedTrack>(
        """{"path":"/favorites","title":"Favorites","children":[
          {"title":"Playlist","artwork":{"light":"https://e.example/l.png","dark":"https://e.example/d.png"}}
        ]}"""
      )
    assertEquals(
      "https://e.example/d.png",
      resolved.toNitro().children?.first()?.artwork?.asSecondOrNull()?.dark,
    )
  }

  @Test
  fun `round trips both shapes`() {
    for (artwork in listOf(JsonArtwork.Single("a.png"), JsonArtwork.Variants("l.png", "d.png"))) {
      val encoded = json.encodeToString(JsonTrack(title = "X", artwork = artwork))
      assertEquals(artwork, json.decodeFromString<JsonTrack>(encoded).artwork)
    }
  }
}
