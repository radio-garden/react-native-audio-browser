package com.audiobrowser.player

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossOriginHeaderStripperTest {

  private val api = "https://api.example.com/stream/7".toHttpUrl()

  @Test
  fun `same origin ignores the path`() {
    assertTrue(
      CrossOriginHeaderStripper.sameOrigin(
        api,
        "https://api.example.com/signed/track.mp3".toHttpUrl(),
      )
    )
  }

  @Test
  fun `a different host, port or scheme is a different origin`() {
    assertFalse(
      CrossOriginHeaderStripper.sameOrigin(api, "https://cdn.example.com/track.mp3".toHttpUrl())
    )
    assertFalse(
      CrossOriginHeaderStripper.sameOrigin(
        api,
        "https://api.example.com:8443/track.mp3".toHttpUrl(),
      )
    )
    assertFalse(
      CrossOriginHeaderStripper.sameOrigin(api, "http://api.example.com/track.mp3".toHttpUrl())
    )
  }

  @Test
  fun `strips every caller header, whatever its name`() {
    val request =
      Request.Builder()
        .url(api)
        .header("Authorization", "Bearer token")
        .header("X-Api-Key", "k")
        .header("Cookie", "session=abc")
        .build()

    val stripped = CrossOriginHeaderStripper.stripCallerHeaders(request)

    assertNull(stripped.header("Authorization"))
    assertNull(stripped.header("X-Api-Key"))
    assertNull(stripped.header("Cookie"))
  }

  @Test
  fun `keeps the transport headers the hop needs`() {
    val request =
      Request.Builder()
        .url(api)
        .header("Range", "bytes=1024-")
        .header("accept-encoding", "identity")
        .header("User-Agent", "react-native-audio-browser")
        .header("X-Api-Key", "k")
        .build()

    val stripped = CrossOriginHeaderStripper.stripCallerHeaders(request)

    assertEquals("bytes=1024-", stripped.header("Range"))
    assertEquals("identity", stripped.header("Accept-Encoding"))
    assertEquals("react-native-audio-browser", stripped.header("User-Agent"))
    assertNull(stripped.header("X-Api-Key"))
  }

  @Test
  fun `keeps media3's ICY metadata request across a hop`() {
    val request =
      Request.Builder().url(api).header("Icy-MetaData", "1").header("X-Api-Key", "k").build()

    val stripped = CrossOriginHeaderStripper.stripCallerHeaders(request)

    assertEquals("1", stripped.header("Icy-MetaData"))
    assertNull(stripped.header("X-Api-Key"))
  }
}
