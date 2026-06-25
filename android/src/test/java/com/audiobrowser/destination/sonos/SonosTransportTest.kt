package com.audiobrowser.destination.sonos

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
        renderingControlControlUrl = "$base/MediaRenderer/RenderingControl/Control",
      )
    transport = SonosTransport(device, SoapClient(OkHttpClient()))
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun okResponse(body: String = "<ok/>") =
    MockResponse().setResponseCode(200).setBody(body)

  @Test
  fun `setUriAndPlay sends SetAVTransportURI (rewritten + didl) then Play`() {
    server.enqueue(okResponse()) // SetAVTransportURI
    server.enqueue(okResponse()) // Play

    transport.setUriAndPlay(
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
      setUriBody.contains("<CurrentURI>x-rincon-mp3radio://ice.example/stream</CurrentURI>"),
    )
    // DIDL is present (double-escaped) with the title.
    assertEquals(true, setUriBody.contains("&lt;dc:title&gt;Jazz FM&lt;/dc:title&gt;"))

    val play = server.takeRequest()
    assertEquals(
      "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
      play.getHeader("SOAPACTION"),
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
  fun `setVolume targets the RenderingControl endpoint and getVolume parses`() {
    server.enqueue(okResponse())
    transport.setVolume(33)
    val req = server.takeRequest()
    assertEquals("/MediaRenderer/RenderingControl/Control", req.path)
    assertEquals(true, req.body.readUtf8().contains("<DesiredVolume>33</DesiredVolume>"))

    server.enqueue(
      okResponse(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
          "<s:Body><u:GetVolumeResponse><CurrentVolume>33</CurrentVolume>" +
          "</u:GetVolumeResponse></s:Body></s:Envelope>"
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
