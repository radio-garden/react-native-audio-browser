package com.audiobrowser.sonos

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Command -> SOAP mapping for [SonosTransport], verified against a local MockWebServer standing in
 * for a Sonos speaker's control endpoints.
 */
class SonosTransportTest {

  private lateinit var server: MockWebServer
  private lateinit var device: SonosDevice
  private lateinit var transport: SonosTransport

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    val base = server.url("/").toString().trimEnd('/')
    device =
      SonosDevice(
        udn = "uuid:RINCON_TEST",
        name = "Test Room",
        baseUrl = base,
        avTransportControlUrl = "$base/MediaRenderer/AVTransport/Control",
        groupRenderingControlUrl = "$base/MediaRenderer/GroupRenderingControl/Control",
        zoneGroupTopologyControlUrl = "$base/ZoneGroupTopology/Control",
      )
    transport = SonosTransport(device, SoapClient(OkHttpClient()))
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun okResponse(body: String = "<ok/>") = MockResponse().setResponseCode(200).setBody(body)

  @Test
  fun `setUri sends SetAVTransportURI with the speaker's URL and the DIDL`() {
    server.enqueue(okResponse())

    transport.setUri(
      streamUrl = "http://ice.example/stream",
      title = "Jazz FM",
      artist = "Various",
      album = null,
      artworkUri = null,
      live = true,
    )

    val setUri = server.takeRequest()
    assertEquals("/MediaRenderer/AVTransport/Control", setUri.path)
    assertEquals(
      "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
      setUri.getHeader("SOAPACTION"),
    )
    val setUriBody = setUri.body.readUtf8()
    // Stream URL rewritten to the Sonos radio scheme, escaped inside CurrentURI.
    assertEquals(
      true,
      setUriBody.contains("<CurrentURI>x-rincon-mp3radio://http://ice.example/stream</CurrentURI>"),
    )
    // DIDL is present (double-escaped) with the title.
    assertEquals(true, setUriBody.contains("&lt;dc:title&gt;Jazz FM&lt;/dc:title&gt;"))
  }

  @Test
  fun `setUri sends a track by its URL`() {
    server.enqueue(okResponse())

    transport.setUri(
      streamUrl = "https://h/song.mp3",
      title = "Song",
      artist = null,
      album = null,
      artworkUri = null,
      live = false,
    )

    assertEquals(
      true,
      server.takeRequest().body.readUtf8().contains("<CurrentURI>https://h/song.mp3</CurrentURI>"),
    )
  }

  @Test
  fun `pause stop play map to their AVTransport actions`() {
    server.enqueue(okResponse())
    transport.pause()
    assertEquals(
      "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\"",
      server.takeRequest().getHeader("SOAPACTION"),
    )

    server.enqueue(okResponse())
    transport.stop()
    assertEquals(
      "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"",
      server.takeRequest().getHeader("SOAPACTION"),
    )

    server.enqueue(okResponse())
    transport.play()
    assertEquals(
      "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
      server.takeRequest().getHeader("SOAPACTION"),
    )
  }

  @Test
  fun `getTransportState parses the CurrentTransportState`() {
    server.enqueue(
      okResponse(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
          "<s:Body><u:GetTransportInfoResponse>" +
          "<CurrentTransportState>PLAYING</CurrentTransportState>" +
          "</u:GetTransportInfoResponse></s:Body></s:Envelope>"
      )
    )
    assertEquals("PLAYING", transport.getTransportState())
  }

  @Test
  fun `getPosition reads the title, position and duration from GetPositionInfo`() {
    val didl =
      "<DIDL-Lite xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">" +
        "<item><r:streamContent>Santana - Smooth</r:streamContent></item></DIDL-Lite>"
    server.enqueue(
      okResponse(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
          "<s:Body><u:GetPositionInfoResponse><TrackDuration>0:03:21</TrackDuration>" +
          "<RelTime>0:01:02</RelTime><TrackMetaData>" +
          didl
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;") +
          "</TrackMetaData></u:GetPositionInfoResponse></s:Body></s:Envelope>"
      )
    )
    assertEquals(SonosPosition("Santana - Smooth", 62_000, 201_000), transport.getPosition())
    assertEquals(
      "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"",
      server.takeRequest().getHeader("SOAPACTION"),
    )
  }

  @Test
  fun `the volume is the group's, through GroupRenderingControl`() {
    server.enqueue(okResponse())
    transport.setVolume(33)
    val req = server.takeRequest()
    assertEquals("/MediaRenderer/GroupRenderingControl/Control", req.path)
    assertEquals(true, req.body.readUtf8().contains("<DesiredVolume>33</DesiredVolume>"))

    server.enqueue(
      okResponse(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
          "<s:Body><u:GetGroupVolumeResponse><CurrentVolume>33</CurrentVolume>" +
          "</u:GetGroupVolumeResponse></s:Body></s:Envelope>"
      )
    )
    assertEquals(33, transport.getVolume())
  }

  @Test
  fun `a SOAP fault surfaces as SoapException`() {
    server.enqueue(
      MockResponse()
        .setResponseCode(500)
        .setBody(
          "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Body><s:Fault><detail><UPnPError " +
            "xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>701</errorCode>" +
            "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        )
    )
    assertThrows(SoapException::class.java) { transport.play() }
  }
}
