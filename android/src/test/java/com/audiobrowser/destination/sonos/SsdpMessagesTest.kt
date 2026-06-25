package com.audiobrowser.destination.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-level build + parse of SSDP messages used for Sonos (ZonePlayer) discovery. */
class SsdpMessagesTest {

  @Test
  fun `buildMSearch produces a canonical ZonePlayer M-SEARCH datagram`() {
    val text = String(SsdpMessages.buildMSearch(), Charsets.UTF_8)
    val expected =
      "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 1\r\n" +
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
        "\r\n"
    assertEquals(expected, text)
  }

  @Test
  fun `buildMSearch honours a custom search target and mx`() {
    val text = String(SsdpMessages.buildMSearch(searchTarget = "ssdp:all", mx = 3), Charsets.UTF_8)
    assertTrue(text.contains("ST: ssdp:all\r\n"))
    assertTrue(text.contains("MX: 3\r\n"))
    // Always terminated by a blank line.
    assertTrue(text.endsWith("\r\n\r\n"))
  }

  @Test
  fun `parseResponse extracts location, usn and st from a Sonos reply`() {
    val raw =
      "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "EXT:\r\n" +
        "LOCATION: http://192.168.1.50:1400/xml/device_description.xml\r\n" +
        "SERVER: Linux UPnP/1.0 Sonos/79.1-65190\r\n" +
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
        "USN: uuid:RINCON_949F3EC213E001400::urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
        "\r\n"
    val r = SsdpMessages.parseResponse(raw)!!
    assertEquals("http://192.168.1.50:1400/xml/device_description.xml", r.location)
    assertEquals("uuid:RINCON_949F3EC213E001400::urn:schemas-upnp-org:device:ZonePlayer:1", r.usn)
    assertEquals("urn:schemas-upnp-org:device:ZonePlayer:1", r.searchTarget)
    assertEquals("Linux UPnP/1.0 Sonos/79.1-65190", r.server)
  }

  @Test
  fun `parseResponse is case-insensitive on header names and trims values`() {
    val raw =
      "HTTP/1.1 200 OK\r\n" +
        "location:   http://10.0.0.7:1400/xml/device_description.xml  \r\n" +
        "usn: uuid:RINCON_ABC::x\r\n" +
        "\r\n"
    val r = SsdpMessages.parseResponse(raw)!!
    assertEquals("http://10.0.0.7:1400/xml/device_description.xml", r.location)
    assertEquals("uuid:RINCON_ABC::x", r.usn)
  }

  @Test
  fun `parseResponse tolerates bare LF line endings`() {
    val raw =
      "HTTP/1.1 200 OK\n" +
        "LOCATION: http://10.0.0.8:1400/xml/device_description.xml\n" +
        "USN: uuid:RINCON_DEF::x\n" +
        "\n"
    val r = SsdpMessages.parseResponse(raw)!!
    assertEquals("http://10.0.0.8:1400/xml/device_description.xml", r.location)
  }

  @Test
  fun `parseResponse returns null for a non-200 status line`() {
    val raw =
      "NOTIFY * HTTP/1.1\r\n" +
        "LOCATION: http://10.0.0.9:1400/xml/device_description.xml\r\n" +
        "USN: uuid:RINCON_GHI::x\r\n" +
        "\r\n"
    assertNull(SsdpMessages.parseResponse(raw))
  }

  @Test
  fun `parseResponse returns null when LOCATION is missing`() {
    val raw =
      "HTTP/1.1 200 OK\r\n" +
        "USN: uuid:RINCON_JKL::x\r\n" +
        "\r\n"
    assertNull(SsdpMessages.parseResponse(raw))
  }

  @Test
  fun `parseResponse returns null when USN is missing`() {
    val raw =
      "HTTP/1.1 200 OK\r\n" +
        "LOCATION: http://10.0.0.10:1400/xml/device_description.xml\r\n" +
        "\r\n"
    assertNull(SsdpMessages.parseResponse(raw))
  }
}
