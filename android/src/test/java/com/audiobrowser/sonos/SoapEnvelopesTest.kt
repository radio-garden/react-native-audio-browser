package com.audiobrowser.sonos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-exact SOAP request bodies + SOAPAction headers for the UPnP actions we issue. */
class SoapEnvelopesTest {

  private val avt = "urn:schemas-upnp-org:service:AVTransport:1"
  private val grc = "urn:schemas-upnp-org:service:GroupRenderingControl:1"

  private fun envelope(service: String, action: String, args: String): String =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
      "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
      "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
      "<s:Body>" +
      "<u:$action xmlns:u=\"$service\">$args</u:$action>" +
      "</s:Body></s:Envelope>"

  @Test
  fun `play`() {
    val a = SoapEnvelopes.play()
    assertEquals("$avt#Play", a.soapAction)
    assertEquals(envelope(avt, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"), a.body)
  }

  @Test
  fun `pause`() {
    val a = SoapEnvelopes.pause()
    assertEquals("$avt#Pause", a.soapAction)
    assertEquals(envelope(avt, "Pause", "<InstanceID>0</InstanceID>"), a.body)
  }

  @Test
  fun `stop`() {
    val a = SoapEnvelopes.stop()
    assertEquals("$avt#Stop", a.soapAction)
    assertEquals(envelope(avt, "Stop", "<InstanceID>0</InstanceID>"), a.body)
  }

  @Test
  fun `getTransportInfo`() {
    val a = SoapEnvelopes.getTransportInfo()
    assertEquals("$avt#GetTransportInfo", a.soapAction)
    assertEquals(envelope(avt, "GetTransportInfo", "<InstanceID>0</InstanceID>"), a.body)
  }

  @Test
  fun `getPositionInfo`() {
    val a = SoapEnvelopes.getPositionInfo()
    assertEquals("$avt#GetPositionInfo", a.soapAction)
    assertEquals(envelope(avt, "GetPositionInfo", "<InstanceID>0</InstanceID>"), a.body)
  }

  @Test
  fun `getZoneGroupState`() {
    val zgt = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    val a = SoapEnvelopes.getZoneGroupState()
    assertEquals("$zgt#GetZoneGroupState", a.soapAction)
    assertEquals(envelope(zgt, "GetZoneGroupState", ""), a.body)
  }

  @Test
  fun `setAvTransportUri escapes the url and double-escapes the didl metadata`() {
    val a = SoapEnvelopes.setAvTransportUri("http://h/s?a=1&b=2", "<DIDL-Lite>x</DIDL-Lite>")
    assertEquals("$avt#SetAVTransportURI", a.soapAction)
    assertEquals(
      envelope(
        avt,
        "SetAVTransportURI",
        "<InstanceID>0</InstanceID>" +
          "<CurrentURI>http://h/s?a=1&amp;b=2</CurrentURI>" +
          "<CurrentURIMetaData>&lt;DIDL-Lite&gt;x&lt;/DIDL-Lite&gt;</CurrentURIMetaData>",
      ),
      a.body,
    )
  }

  @Test
  fun `setGroupVolume clamps to 0 through 100`() {
    assertEquals(
      envelope(
        grc,
        "SetGroupVolume",
        "<InstanceID>0</InstanceID><DesiredVolume>50</DesiredVolume>",
      ),
      SoapEnvelopes.setGroupVolume(50).body,
    )
    assertEquals("$grc#SetGroupVolume", SoapEnvelopes.setGroupVolume(50).soapAction)
    assertEquals(
      "<DesiredVolume>100</DesiredVolume>",
      Regex("<DesiredVolume>.*?</DesiredVolume>")
        .find(SoapEnvelopes.setGroupVolume(150).body)!!
        .value,
    )
    assertEquals(
      "<DesiredVolume>0</DesiredVolume>",
      Regex("<DesiredVolume>.*?</DesiredVolume>")
        .find(SoapEnvelopes.setGroupVolume(-5).body)!!
        .value,
    )
  }

  @Test
  fun `getGroupVolume`() {
    val a = SoapEnvelopes.getGroupVolume()
    assertEquals("$grc#GetGroupVolume", a.soapAction)
    assertEquals(envelope(grc, "GetGroupVolume", "<InstanceID>0</InstanceID>"), a.body)
  }

  @Test
  fun `seek goes to a relative time, to the second`() {
    assertEquals(
      envelope(
        "urn:schemas-upnp-org:service:AVTransport:1",
        "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>1:01:01</Target>",
      ),
      SoapEnvelopes.seek(3_661_500).body,
    )
  }
}
