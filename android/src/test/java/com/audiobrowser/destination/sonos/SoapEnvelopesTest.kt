package com.audiobrowser.destination.sonos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-exact SOAP request bodies + SOAPAction headers for the UPnP actions we issue. */
class SoapEnvelopesTest {

  private val avt = "urn:schemas-upnp-org:service:AVTransport:1"
  private val rc = "urn:schemas-upnp-org:service:RenderingControl:1"

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
  fun `setVolume clamps to 0 through 100 and targets the Master channel`() {
    assertEquals(
      envelope(
        rc,
        "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>50</DesiredVolume>",
      ),
      SoapEnvelopes.setVolume(50).body,
    )
    assertEquals("$rc#SetVolume", SoapEnvelopes.setVolume(50).soapAction)
    // Clamping.
    assertEquals(
      "<DesiredVolume>100</DesiredVolume>",
      Regex("<DesiredVolume>.*?</DesiredVolume>").find(SoapEnvelopes.setVolume(150).body)!!.value,
    )
    assertEquals(
      "<DesiredVolume>0</DesiredVolume>",
      Regex("<DesiredVolume>.*?</DesiredVolume>").find(SoapEnvelopes.setVolume(-5).body)!!.value,
    )
  }

  @Test
  fun `getVolume`() {
    val a = SoapEnvelopes.getVolume()
    assertEquals("$rc#GetVolume", a.soapAction)
    assertEquals(
      envelope(rc, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"),
      a.body,
    )
  }

  @Test
  fun `setMute maps boolean to 1 or 0`() {
    assertEquals(
      envelope(
        rc,
        "SetMute",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>1</DesiredMute>",
      ),
      SoapEnvelopes.setMute(true).body,
    )
    assertEquals(
      envelope(
        rc,
        "SetMute",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>0</DesiredMute>",
      ),
      SoapEnvelopes.setMute(false).body,
    )
  }
}
