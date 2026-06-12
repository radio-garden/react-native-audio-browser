package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkResolutionRegistryTest {

  private fun track(title: String) =
    Track(
      id = null,
      url = null,
      src = "https://s/$title.mp3",
      artwork = null,
      artworkSource = null,
      request = null,
      artworkCarPlayTinted = null,
      title = title,
      subtitle = null,
      artist = null,
      albumUrl = null,
      album = null,
      description = null,
      genre = null,
      duration = null,
      style = null,
      childrenStyle = null,
      favorited = null,
      groupTitle = null,
      live = null,
      imageRow = null,
    )

  @Test
  fun `lookup returns the registered entry`() {
    val registry = ArtworkResolutionRegistry()
    registry.register("https://img/a.png?sig=1", track("a"), perRouteConfig = null)
    assertEquals("a", registry.lookup("https://img/a.png?sig=1")?.track?.title)
    assertNull(registry.lookup("https://img/unknown.png"))
  }

  @Test
  fun `re-registering a uri overwrites the entry`() {
    val registry = ArtworkResolutionRegistry()
    registry.register("u", track("old"), null)
    registry.register("u", track("new"), null)
    assertEquals("new", registry.lookup("u")?.track?.title)
  }

  @Test
  fun `evicts least-recently-used beyond capacity`() {
    val registry = ArtworkResolutionRegistry(maxEntries = 2)
    registry.register("u1", track("t1"), null)
    registry.register("u2", track("t2"), null)
    registry.lookup("u1") // touch u1 so u2 is eldest
    registry.register("u3", track("t3"), null)
    assertEquals("t1", registry.lookup("u1")?.track?.title)
    assertNull(registry.lookup("u2"))
  }
}
