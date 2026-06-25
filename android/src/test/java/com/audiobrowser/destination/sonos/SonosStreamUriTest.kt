package com.audiobrowser.destination.sonos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sonos plays a raw MP3/ICY radio stream reliably only when the http(s) URL is handed to
 * `SetAVTransportURI` under the `x-rincon-mp3radio://` scheme. Segmented/known-container formats
 * (HLS, DASH, AAC, FLAC…) are played over plain http(s). [SonosStreamUri] encodes that rewrite.
 */
class SonosStreamUriTest {

  @Test
  fun `rewrites an extensionless http radio mount to x-rincon-mp3radio`() {
    assertEquals(
      "x-rincon-mp3radio://ice.example.com/stream",
      SonosStreamUri.forTransport("http://ice.example.com/stream"),
    )
  }

  @Test
  fun `rewrites an explicit mp3 url and preserves the query string`() {
    assertEquals(
      "x-rincon-mp3radio://h.example/radio.mp3?token=abc123",
      SonosStreamUri.forTransport("https://h.example/radio.mp3?token=abc123"),
    )
  }

  @Test
  fun `rewrites when the content type is audio mpeg regardless of path`() {
    assertEquals(
      "x-rincon-mp3radio://h/path/to/thing",
      SonosStreamUri.forTransport("http://h/path/to/thing", contentType = "audio/mpeg"),
    )
  }

  @Test
  fun `leaves HLS and DASH playlists as http(s)`() {
    assertEquals(
      "https://h/live.m3u8",
      SonosStreamUri.forTransport("https://h/live.m3u8"),
    )
    assertEquals("http://h/live.mpd", SonosStreamUri.forTransport("http://h/live.mpd"))
  }

  @Test
  fun `leaves container formats (aac, flac, ogg) as http(s)`() {
    assertEquals("http://h/s.aac", SonosStreamUri.forTransport("http://h/s.aac"))
    assertEquals("http://h/s.flac", SonosStreamUri.forTransport("http://h/s.flac"))
    assertEquals("http://h/s.ogg", SonosStreamUri.forTransport("http://h/s.ogg"))
  }

  @Test
  fun `a known container content type wins over an extensionless path`() {
    assertEquals(
      "http://h/stream",
      SonosStreamUri.forTransport("http://h/stream", contentType = "application/vnd.apple.mpegurl"),
    )
    assertEquals(
      "http://h/stream",
      SonosStreamUri.forTransport("http://h/stream", contentType = "audio/aac"),
    )
  }

  @Test
  fun `leaves already-special Sonos schemes untouched`() {
    assertEquals(
      "x-rincon-mp3radio://h/s",
      SonosStreamUri.forTransport("x-rincon-mp3radio://h/s"),
    )
    assertEquals(
      "x-sonosapi-stream:s?sid=254",
      SonosStreamUri.forTransport("x-sonosapi-stream:s?sid=254"),
    )
  }
}
