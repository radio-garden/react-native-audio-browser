package com.audiobrowser.browser

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A `favorited` that a route's response carries on a track or a page. */
class JsonFavoritedTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun track(body: String) = json.decodeFromString<JsonTrack>(body).toNitro()

  @Test
  fun `decodes the value the response carries`() {
    assertEquals(true, track("""{"title":"X","src":"a","favorited":true}""").favorited)
    assertEquals(false, track("""{"title":"X","src":"a","favorited":false}""").favorited)
    assertNull(track("""{"title":"X","src":"a"}""").favorited)
  }

  @Test
  fun `decodes on a page and on its tracks`() {
    val page =
      json
        .decodeFromString<JsonResolvedTrack>(
          """{"path":"/track/1","title":"Page","src":"p","favorited":true,"sections":[
            {"children":[{"title":"A","src":"a","favorited":true},{"title":"B","src":"b"}]}
          ]}"""
        )
        .toNitro()
    assertEquals(true, page.favorited)
    assertEquals(listOf(true, null), page.sections?.first()?.children?.map { it.favorited })
  }
}
