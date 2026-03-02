package com.audiobrowser.browser

import org.junit.Assert.*
import org.junit.Test

class SimpleRouterTest {

  private val router = SimpleRouter()

  // -- exact matches --

  @Test
  fun `matches a simple path`() {
    val result = router.findBestMatch("/artists", mapOf("/artists" to true))
    assertNotNull(result)
    assertEquals("/artists", result!!.first)
    assertTrue(result.second.params.isEmpty())
  }

  @Test
  fun `matches multi-segment paths`() {
    val result = router.findBestMatch("/artists/top/rated", mapOf("/artists/top/rated" to true))
    assertNotNull(result)
    assertEquals("/artists/top/rated", result!!.first)
  }

  @Test
  fun `returns null when no route matches`() {
    assertNull(router.findBestMatch("/unknown", mapOf("/artists" to true)))
  }

  @Test
  fun `returns null when segment count differs`() {
    assertNull(router.findBestMatch("/artists/123", mapOf("/artists" to true)))
  }

  @Test
  fun `returns null when routes are empty`() {
    assertNull(router.findBestMatch("/artists", emptyMap<String, Boolean>()))
  }

  @Test
  fun `treats trailing slashes the same as without`() {
    val result = router.findBestMatch("/artists/", mapOf("/artists" to true))
    assertNotNull(result)
    assertEquals("/artists", result!!.first)
  }

  // -- parameter extraction --

  @Test
  fun `extracts a single parameter`() {
    val result = router.findBestMatch("/artists/123", mapOf("/artists/{id}" to true))
    assertNotNull(result)
    assertEquals(mapOf("id" to "123"), result!!.second.params)
  }

  @Test
  fun `extracts multiple parameters`() {
    val result =
      router.findBestMatch(
        "/artists/123/albums/456",
        mapOf("/artists/{artistId}/albums/{albumId}" to true),
      )
    assertNotNull(result)
    assertEquals(mapOf("artistId" to "123", "albumId" to "456"), result!!.second.params)
  }

  // -- single wildcard --

  @Test
  fun `matches any single segment`() {
    assertNotNull(router.findBestMatch("/artists/anything", mapOf("/artists/*" to true)))
  }

  @Test
  fun `does not extract wildcard value into params`() {
    val result = router.findBestMatch("/artists/anything", mapOf("/artists/*" to true))
    assertTrue(result!!.second.params.isEmpty())
  }

  // -- tail wildcard --

  @Test
  fun `matches with no remaining segments and has no tail param`() {
    val result = router.findBestMatch("/files", mapOf("/files/**" to true))
    assertNotNull(result)
    assertTrue(result!!.second.params.isEmpty())
  }

  @Test
  fun `matches with remaining segments and captures tail`() {
    val result = router.findBestMatch("/files/a/b/c", mapOf("/files/**" to true))
    assertNotNull(result)
    assertEquals(mapOf("tail" to "a/b/c"), result!!.second.params)
  }

  @Test
  fun `matches with a single remaining segment`() {
    val result = router.findBestMatch("/files/readme.txt", mapOf("/files/**" to true))
    assertNotNull(result)
    assertEquals(mapOf("tail" to "readme.txt"), result!!.second.params)
  }

  @Test
  fun `returns null when prefix segments do not match`() {
    assertNull(router.findBestMatch("/other/a/b", mapOf("/files/**" to true)))
  }

  @Test
  fun `works with parameters before tail wildcard`() {
    val result =
      router.findBestMatch("/api/v1/users/list", mapOf("/api/{version}/**" to true))
    assertNotNull(result)
    assertEquals(mapOf("version" to "v1", "tail" to "users/list"), result!!.second.params)
  }

  @Test
  fun `matches any path with bare tail wildcard pattern`() {
    val result = router.findBestMatch("/any/path/here", mapOf("/**" to true))
    assertNotNull(result)
    assertEquals(mapOf("tail" to "any/path/here"), result!!.second.params)
  }

  // -- specificity --

  @Test
  fun `prefers constant segments over parameters`() {
    val routes = mapOf("/artists/{id}" to true, "/artists/top" to true)
    val result = router.findBestMatch("/artists/top", routes)
    assertEquals("/artists/top", result!!.first)
  }

  @Test
  fun `prefers parameters over wildcards`() {
    val routes = mapOf("/artists/*" to true, "/artists/{id}" to true)
    val result = router.findBestMatch("/artists/123", routes)
    assertEquals("/artists/{id}", result!!.first)
  }

  @Test
  fun `prefers exact match over tail wildcard`() {
    val routes = mapOf("/api/**" to true, "/api/{version}" to true)
    val result = router.findBestMatch("/api/v1", routes)
    assertEquals("/api/{version}", result!!.first)
  }

  @Test
  fun `computes correct specificity for tail wildcard patterns`() {
    // Regression test for issue #27: specificity must account for
    // segment types before the ** wildcard.
    val result = router.findBestMatch("/api/v1/users", mapOf("/api/v1/**" to true))
    // constant "api" (1000) + constant "v1" (1000) + tail(1) + 3 segments = 2004
    assertEquals(2004, result!!.second.specificity)
  }

  @Test
  fun `prefers constant prefix over parameter prefix in tail wildcards`() {
    // Second part of issue #27: with correct specificity, the constant
    // prefix should beat the parameter prefix.
    val routes = mapOf("/api/{version}/**" to true, "/api/v1/**" to true)
    val result = router.findBestMatch("/api/v1/users", routes)
    assertEquals("/api/v1/**", result!!.first)
  }
}
