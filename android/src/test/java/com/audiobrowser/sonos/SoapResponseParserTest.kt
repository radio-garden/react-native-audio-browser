package com.audiobrowser.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing UPnP SOAP responses: transport state, volume, and faults. */
class SoapResponseParserTest {

  private fun transportInfo(state: String): String =
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
      "<s:Body><u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
      "<CurrentTransportState>$state</CurrentTransportState>" +
      "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
      "<CurrentSpeed>1</CurrentSpeed>" +
      "</u:GetTransportInfoResponse></s:Body></s:Envelope>"

  private val fault =
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
      "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
      "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
      "<errorCode>701</errorCode><errorDescription>Transition not available</errorDescription>" +
      "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"

  @Test
  fun `transportState reads CurrentTransportState`() {
    assertEquals("PLAYING", SoapResponseParser.transportState(transportInfo("PLAYING")))
    assertEquals(
      "PAUSED_PLAYBACK",
      SoapResponseParser.transportState(transportInfo("PAUSED_PLAYBACK")),
    )
    assertEquals("STOPPED", SoapResponseParser.transportState(transportInfo("STOPPED")))
  }

  @Test
  fun `volume reads CurrentVolume as an int`() {
    val xml =
      "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
        "<s:Body><u:GetVolumeResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">" +
        "<CurrentVolume>42</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>"
    assertEquals(42, SoapResponseParser.volume(xml))
  }

  @Test
  fun `fault extracts the UPnP error code and description`() {
    val f = SoapResponseParser.fault(fault)!!
    assertEquals(701, f.errorCode)
    assertEquals("Transition not available", f.errorDescription)
  }

  @Test
  fun `fault returns null for a normal response`() {
    assertNull(SoapResponseParser.fault(transportInfo("PLAYING")))
  }

  @Test
  fun `volume returns null on a soft fault response`() {
    assertNull(SoapResponseParser.volume(fault))
  }

  @Test
  fun `transportState returns null on a fault or garbage`() {
    assertNull(SoapResponseParser.transportState(fault))
    assertNull(SoapResponseParser.transportState("not xml at all"))
  }

  private fun positionInfo(trackMetaData: String): String =
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
      "<s:Body><u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
      "<Track>1</Track><TrackDuration>0:00:00</TrackDuration>" +
      "<TrackMetaData>${escape(trackMetaData)}</TrackMetaData>" +
      "<TrackURI>x-rincon-mp3radio://https://h/stream</TrackURI>" +
      "</u:GetPositionInfoResponse></s:Body></s:Envelope>"

  private fun escape(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private fun trackDidl(streamContent: String) =
    "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
      "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
      "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
      "<item id=\"-1\" parentID=\"-1\" restricted=\"true\">" +
      "<res protocolInfo=\"sonos.com-http:*:audio/mpeg:*\">x-rincon-mp3radio://https://h/stream</res>" +
      "<r:streamContent>$streamContent</r:streamContent><r:radioShowMd></r:radioShowMd>" +
      "<upnp:class>object.item</upnp:class></item></DIDL-Lite>"

  @Test
  fun `streamContent reads the title the speaker took from the stream`() {
    assertEquals(
      "Santana - Smooth [3OSN]",
      SoapResponseParser.streamContent(positionInfo(trackDidl("Santana - Smooth [3OSN]"))),
    )
  }

  @Test
  fun `streamContent unescapes the title once more than the envelope`() {
    assertEquals(
      "Simon & Garfunkel - Cecilia",
      SoapResponseParser.streamContent(positionInfo(trackDidl("Simon &amp; Garfunkel - Cecilia"))),
    )
  }

  @Test
  fun `streamContent is null when the stream sends no title`() {
    assertNull(SoapResponseParser.streamContent(positionInfo(trackDidl(""))))
  }

  @Test
  fun `streamContent is null when the speaker has no track metadata`() {
    assertNull(SoapResponseParser.streamContent(positionInfo("NOT_IMPLEMENTED")))
    assertNull(SoapResponseParser.streamContent(positionInfo("")))
    assertNull(SoapResponseParser.streamContent(fault))
  }

  private fun timing(duration: String, relTime: String): String =
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
      "<s:Body><u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
      "<Track>1</Track><TrackDuration>$duration</TrackDuration><TrackMetaData></TrackMetaData>" +
      "<RelTime>$relTime</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>"

  @Test
  fun `position reads a track's position and duration`() {
    assertEquals(
      SonosPosition(streamContent = null, positionMs = 3_723_000, durationMs = 201_500),
      SoapResponseParser.position(timing("0:03:21.500", "1:02:03")),
    )
  }

  @Test
  fun `position has no duration for a live stream, and no time the speaker doesn't keep`() {
    assertEquals(
      SonosPosition(streamContent = null, positionMs = null, durationMs = null),
      SoapResponseParser.position(timing("0:00:00", "NOT_IMPLEMENTED")),
    )
  }

  @Test
  fun `position carries the stream title and track URI, and is null for a fault`() {
    val position = SoapResponseParser.position(positionInfo(trackDidl("Santana - Smooth")))
    assertEquals("Santana - Smooth", position?.streamContent)
    assertEquals("x-rincon-mp3radio://https://h/stream", position?.trackUri)
    assertNull(SoapResponseParser.position(fault))
  }
}
