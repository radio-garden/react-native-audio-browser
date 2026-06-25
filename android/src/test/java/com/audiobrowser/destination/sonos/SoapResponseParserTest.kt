package com.audiobrowser.destination.sonos

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
}
