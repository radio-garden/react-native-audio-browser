package com.audiobrowser.destination.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing a Sonos `device_description.xml` into a [SonosDevice] with absolute control URLs. */
class DeviceDescriptionParserTest {

  private fun fixture(name: String): String =
    javaClass.getResource("/sonos/$name")!!.readText()

  private val location = "http://192.168.1.50:1400/xml/device_description.xml"

  @Test
  fun `parses udn, name and resolves nested control URLs against the base`() {
    val device = DeviceDescriptionParser.parse(fixture("device_description.xml"), location)!!
    assertEquals("uuid:RINCON_949F3EC213E001400", device.udn)
    // AVTransport + RenderingControl live on the embedded MediaRenderer, not the root device.
    assertEquals(
      "http://192.168.1.50:1400/MediaRenderer/AVTransport/Control",
      device.avTransportControlUrl,
    )
    assertEquals(
      "http://192.168.1.50:1400/MediaRenderer/RenderingControl/Control",
      device.renderingControlControlUrl,
    )
    assertEquals("http://192.168.1.50:1400", device.baseUrl)
  }

  @Test
  fun `prefers the Sonos roomName as the friendly name`() {
    val device = DeviceDescriptionParser.parse(fixture("device_description.xml"), location)!!
    assertEquals("Kitchen", device.name)
  }

  @Test
  fun `resolves an absolute controlURL as-is`() {
    val xml =
      """
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
        <friendlyName>Office</friendlyName>
        <manufacturer>Sonos, Inc.</manufacturer>
        <UDN>uuid:RINCON_ABS</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>http://10.0.0.5:1400/AVT/Control</controlURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/RC/Control</controlURL>
          </service>
        </serviceList>
      </device></root>
      """
        .trimIndent()
    val device = DeviceDescriptionParser.parse(xml, "http://10.0.0.5:1400/desc.xml")!!
    assertEquals("http://10.0.0.5:1400/AVT/Control", device.avTransportControlUrl)
    assertEquals("http://10.0.0.5:1400/RC/Control", device.renderingControlControlUrl)
  }

  @Test
  fun `rejects a non-Sonos device`() {
    val xml =
      """
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Some DLNA TV</friendlyName>
        <manufacturer>Acme Corp</manufacturer>
        <UDN>uuid:NOT_SONOS</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/AVT/Control</controlURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/RC/Control</controlURL>
          </service>
        </serviceList>
      </device></root>
      """
        .trimIndent()
    assertNull(DeviceDescriptionParser.parse(xml, "http://10.0.0.6:1400/desc.xml"))
  }

  @Test
  fun `returns null when AVTransport service is absent`() {
    val xml =
      """
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
        <friendlyName>Bedroom</friendlyName>
        <manufacturer>Sonos, Inc.</manufacturer>
        <UDN>uuid:RINCON_NOAVT</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/RC/Control</controlURL>
          </service>
        </serviceList>
      </device></root>
      """
        .trimIndent()
    assertNull(DeviceDescriptionParser.parse(xml, "http://10.0.0.7:1400/desc.xml"))
  }

  @Test
  fun `returns null on malformed XML`() {
    assertNull(DeviceDescriptionParser.parse("<root><device", location))
  }
}
