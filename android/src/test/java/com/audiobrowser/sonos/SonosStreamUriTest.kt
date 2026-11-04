package com.audiobrowser.sonos

import org.junit.Assert.assertEquals
import org.junit.Test

class SonosStreamUriTest {

  @Test
  fun `keeps the https scheme inside x-rincon-mp3radio`() {
    assertEquals(
      "x-rincon-mp3radio://https://ice.example.com/stream",
      SonosStreamUri.forTransport("https://ice.example.com/stream"),
    )
  }

  @Test
  fun `keeps the http scheme inside x-rincon-mp3radio`() {
    assertEquals(
      "x-rincon-mp3radio://http://ice.example.com/stream",
      SonosStreamUri.forTransport("http://ice.example.com/stream"),
    )
  }

  @Test
  fun `wraps every format alike, leaving the speaker to sniff the codec`() {
    for (url in
      listOf(
        "https://h/live.m3u8",
        "https://h/s.aac",
        "http://h/radio.mp3?token=abc&x=1",
        "https://h/listen/abc/channel.mp3",
      )) {
      assertEquals("x-rincon-mp3radio://$url", SonosStreamUri.forTransport(url))
    }
  }

  @Test
  fun `matches the scheme case-insensitively`() {
    assertEquals("x-rincon-mp3radio://HTTPS://h/s", SonosStreamUri.forTransport("HTTPS://h/s"))
  }

  @Test
  fun `leaves a Sonos scheme untouched`() {
    assertEquals(
      "x-rincon-mp3radio://https://h/s",
      SonosStreamUri.forTransport("x-rincon-mp3radio://https://h/s"),
    )
    assertEquals(
      "x-sonosapi-stream:s?sid=254",
      SonosStreamUri.forTransport("x-sonosapi-stream:s?sid=254"),
    )
  }
}
