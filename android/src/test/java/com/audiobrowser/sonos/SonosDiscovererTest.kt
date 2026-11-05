package com.audiobrowser.sonos

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SonosDiscovererTest {

  private lateinit var server: MockWebServer
  private val sonosXml = javaClass.getResource("/sonos/device_description.xml")!!.readText()
  private var topologyResponse: MockResponse = MockResponse().setResponseCode(500)
  private var topologyCalls = 0
  private var firstTopology: MockResponse? = null

  @Before
  fun setUp() {
    server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.requestUrl?.encodedPath) {
            "/xml/device_description.xml" -> MockResponse().setBody(sonosXml)
            "/ZoneGroupTopology/Control" -> {
              topologyCalls++
              firstTopology?.also { firstTopology = null } ?: topologyResponse
            }
            else -> MockResponse().setResponseCode(404)
          }
      }
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private val location
    get() = server.url("/xml/device_description.xml").toString()

  private fun scannerReturning(vararg locations: String) =
    object : SsdpScanner {
      override fun search(timeoutMs: Int, repeats: Int): List<SsdpResponse> =
        locations.mapIndexed { i, location ->
          SsdpResponse(location = location, usn = "uuid:$i", searchTarget = null, server = null)
        }
    }

  private fun topology(vararg groups: Pair<String, String>): MockResponse {
    val state =
      "<ZoneGroupState><ZoneGroups>" +
        groups.joinToString("") { (uuid, zone) ->
          "<ZoneGroup Coordinator=\"$uuid\" ID=\"$uuid:1\">" +
            "<ZoneGroupMember UUID=\"$uuid\" Location=\"$location\" ZoneName=\"$zone\"/>" +
            "</ZoneGroup>"
        } +
        "</ZoneGroups></ZoneGroupState>"
    return envelope(state)
  }

  private fun envelope(state: String): MockResponse {
    val escaped =
      state.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    return MockResponse()
      .setBody(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
          "<u:GetZoneGroupStateResponse xmlns:u=\"urn:schemas-upnp-org:service:ZoneGroupTopology:1\">" +
          "<ZoneGroupState>$escaped</ZoneGroupState></u:GetZoneGroupStateResponse></s:Body></s:Envelope>"
      )
  }

  @Test
  fun `lists the rooms of the topology, each at its coordinator`() {
    topologyResponse = topology("RINCON_A" to "Dining Room", "RINCON_C" to "Living Room")

    val rooms = SonosDiscoverer(OkHttpClient(), scannerReturning(location)).discover()

    assertEquals(listOf("RINCON_A", "RINCON_C"), rooms.map { it.id })
    assertEquals(listOf("Dining Room", "Living Room"), rooms.map { it.name })
    assertEquals(
      server.url("/MediaRenderer/AVTransport/Control").toString(),
      rooms[0].device.avTransportControlUrl,
    )
  }

  @Test
  fun `asks one speaker for the topology, however many answered the search`() {
    topologyResponse = topology("RINCON_A" to "Dining Room")

    // Three locations of the one mock speaker, which the discoverer takes for three speakers.
    SonosDiscoverer(OkHttpClient(), scannerReturning(location, "$location?b", "$location?c"))
      .discover()

    assertEquals(1, topologyCalls)
  }

  @Test
  fun `moves on to the next speaker when one cannot be read`() {
    topologyResponse = topology("RINCON_A" to "Dining Room")
    val unreadable = server.url("/gone.xml").toString()

    val rooms = SonosDiscoverer(OkHttpClient(), scannerReturning(unreadable, location)).discover()

    assertEquals(listOf("Dining Room"), rooms.map { it.name })
  }

  @Test
  fun `skips the topology of a stereo pair's hidden half, which leaves out its own pair`() {
    topologyResponse = topology("RINCON_A" to "Dining Room")
    val hidden = MockWebServer()
    val hiddenTopology =
      "<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator=\"RINCON_H\" ID=\"RINCON_H:1\">" +
        "<ZoneGroupMember UUID=\"RINCON_H\" Location=\"${hidden.url("/xml/device_description.xml")}\" " +
        "ZoneName=\"Dining Room\" Invisible=\"1\"/></ZoneGroup></ZoneGroups></ZoneGroupState>"
    hidden.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.requestUrl?.encodedPath) {
            "/xml/device_description.xml" ->
              // The fixture's speaker as RINCON_H, the member its topology marks hidden.
              MockResponse().setBody(sonosXml.replace("RINCON_949F3EC213E001400", "RINCON_H"))
            "/ZoneGroupTopology/Control" -> envelope(hiddenTopology)
            else -> MockResponse().setResponseCode(404)
          }
      }
    hidden.start()
    try {
      val rooms =
        SonosDiscoverer(
            OkHttpClient(),
            scannerReturning(hidden.url("/xml/device_description.xml").toString(), location),
          )
          .discover()

      assertEquals(listOf("RINCON_A"), rooms.map { it.id })
    } finally {
      hidden.shutdown()
    }
  }

  @Test
  fun `moves on to the next speaker when one's topology can't be read`() {
    firstTopology = MockResponse().setBody("<s:Envelope><s:Body></s:Body></s:Envelope>")
    topologyResponse = topology("RINCON_A" to "Dining Room")

    val rooms =
      SonosDiscoverer(OkHttpClient(), scannerReturning(location, "$location?b")).discover()

    assertEquals(listOf("Dining Room"), rooms.map { it.name })
  }

  @Test
  fun `finds no rooms when the speakers refuse UPnP`() {
    topologyResponse = MockResponse().setResponseCode(403)

    assertEquals(
      emptyList<SonosRoom>(),
      SonosDiscoverer(OkHttpClient(), scannerReturning(location)).discover(),
    )
  }

  @Test
  fun `fetchDevice returns null on an http error`() {
    assertNull(
      SonosDiscoverer(OkHttpClient(), scannerReturning())
        .fetchDevice(server.url("/gone.xml").toString())
    )
  }
}
