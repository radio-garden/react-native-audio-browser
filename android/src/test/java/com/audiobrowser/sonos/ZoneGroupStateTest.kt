package com.audiobrowser.sonos

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneGroupStateTest {

  private fun response(zoneGroupState: String): String =
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
      "<s:Body><u:GetZoneGroupStateResponse " +
      "xmlns:u=\"urn:schemas-upnp-org:service:ZoneGroupTopology:1\">" +
      "<ZoneGroupState>${escape(zoneGroupState)}</ZoneGroupState>" +
      "</u:GetZoneGroupStateResponse></s:Body></s:Envelope>"

  private fun escape(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private fun member(uuid: String, ip: String, zone: String, extra: String = "") =
    "<ZoneGroupMember UUID=\"$uuid\" Location=\"http://$ip:1400/xml/device_description.xml\" " +
      "ZoneName=\"$zone\" $extra/>"

  @Test
  fun `lists a stereo pair as one room, at its coordinator`() {
    val state =
      "<ZoneGroupState><ZoneGroups>" +
        "<ZoneGroup Coordinator=\"RINCON_A\" ID=\"RINCON_A:1\">" +
        member(
          "RINCON_A",
          "192.168.1.31",
          "Dining Room",
          "ChannelMapSet=\"RINCON_A:LF,LF;RINCON_B:RF,RF\"",
        ) +
        member("RINCON_B", "192.168.1.42", "Dining Room", "Invisible=\"1\"") +
        "</ZoneGroup>" +
        "<ZoneGroup Coordinator=\"RINCON_C\" ID=\"RINCON_C:7\">" +
        member("RINCON_D", "192.168.1.41", "Living Room", "Invisible=\"1\"") +
        member("RINCON_C", "192.168.1.39", "Living Room") +
        "</ZoneGroup>" +
        "</ZoneGroups><VanishedDevices/></ZoneGroupState>"

    assertEquals(
      listOf(
        SonosGroup(
          coordinator = "RINCON_A",
          location = "http://192.168.1.31:1400/xml/device_description.xml",
          name = "Dining Room",
        ),
        SonosGroup(
          coordinator = "RINCON_C",
          location = "http://192.168.1.39:1400/xml/device_description.xml",
          name = "Living Room",
        ),
      ),
      ZoneGroupState.parse(response(state)),
    )
  }

  @Test
  fun `skips the hidden half of a stereo pair reported as a group of its own`() {
    val state =
      "<ZoneGroupState><ZoneGroups>" +
        "<ZoneGroup Coordinator=\"RINCON_A\" ID=\"RINCON_A:1\">" +
        member("RINCON_A", "10.0.0.31", "Dining Room") +
        "</ZoneGroup>" +
        "<ZoneGroup Coordinator=\"RINCON_B\" ID=\"RINCON_B:2\">" +
        member("RINCON_B", "10.0.0.42", "Dining Room", "Invisible=\"1\"") +
        "</ZoneGroup>" +
        "</ZoneGroups></ZoneGroupState>"

    assertEquals(listOf("RINCON_A"), ZoneGroupState.parse(response(state)).map { it.coordinator })
  }

  @Test
  fun `names a group of rooms after its coordinator and counts the rest`() {
    val state =
      "<ZoneGroupState><ZoneGroups>" +
        "<ZoneGroup Coordinator=\"RINCON_K\" ID=\"RINCON_K:3\">" +
        member("RINCON_O", "10.0.0.3", "Office") +
        member("RINCON_K", "10.0.0.2", "Kitchen") +
        member("RINCON_P", "10.0.0.4", "Patio") +
        "</ZoneGroup>" +
        "</ZoneGroups></ZoneGroupState>"

    assertEquals(listOf("Kitchen + 2"), ZoneGroupState.parse(response(state)).map { it.name })
  }

  @Test
  fun `does not count a home theatre's satellites as rooms`() {
    val state =
      "<ZoneGroupState><ZoneGroups>" +
        "<ZoneGroup Coordinator=\"RINCON_T\" ID=\"RINCON_T:9\">" +
        "<ZoneGroupMember UUID=\"RINCON_T\" " +
        "Location=\"http://10.0.0.9:1400/xml/device_description.xml\" ZoneName=\"TV Room\">" +
        "<Satellite UUID=\"RINCON_S\" Location=\"http://10.0.0.10:1400/xml/device_description.xml\" " +
        "ZoneName=\"TV Room\" Invisible=\"1\"/>" +
        "</ZoneGroupMember>" +
        "</ZoneGroup>" +
        "</ZoneGroups></ZoneGroupState>"

    assertEquals(
      listOf(
        SonosGroup(
          coordinator = "RINCON_T",
          location = "http://10.0.0.9:1400/xml/device_description.xml",
          name = "TV Room",
        )
      ),
      ZoneGroupState.parse(response(state)),
    )
  }

  @Test
  fun `reads the older form without the ZoneGroupState wrapper`() {
    val state =
      "<ZoneGroups><ZoneGroup Coordinator=\"RINCON_A\" ID=\"RINCON_A:1\">" +
        member("RINCON_A", "10.0.0.1", "Bedroom") +
        "</ZoneGroup></ZoneGroups>"

    assertEquals(listOf("Bedroom"), ZoneGroupState.parse(response(state)).map { it.name })
  }

  @Test
  fun `skips a group whose coordinator is not among its members`() {
    val state =
      "<ZoneGroupState><ZoneGroups>" +
        "<ZoneGroup Coordinator=\"RINCON_GONE\" ID=\"RINCON_GONE:1\">" +
        member("RINCON_A", "10.0.0.1", "Bedroom") +
        "</ZoneGroup></ZoneGroups></ZoneGroupState>"

    assertEquals(emptyList<SonosGroup>(), ZoneGroupState.parse(response(state)))
  }

  @Test
  fun `is empty for a fault or garbage`() {
    assertEquals(emptyList<SonosGroup>(), ZoneGroupState.parse("not xml"))
    assertEquals(emptyList<SonosGroup>(), ZoneGroupState.parse(response("not xml")))
  }
}
