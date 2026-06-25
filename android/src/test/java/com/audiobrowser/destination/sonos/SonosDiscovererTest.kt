package com.audiobrowser.destination.sonos

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

/** Fetch + parse + dedup orchestration of [SonosDiscoverer] (the SSDP scan is faked). */
class SonosDiscovererTest {

  private lateinit var server: MockWebServer
  private val sonosXml = javaClass.getResource("/sonos/device_description.xml")!!.readText()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  /** A scanner that returns canned responses instead of touching the network. */
  private fun scannerReturning(vararg responses: SsdpResponse) =
    object : SsdpScanner {
      override fun search(timeoutMs: Int, repeats: Int): List<SsdpResponse> = responses.toList()
    }

  private fun ssdp(location: String, usn: String) =
    SsdpResponse(location = location, usn = usn, searchTarget = null, server = "Sonos")

  @Test
  fun `fetchDevice fetches and parses a device description`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody(sonosXml))
    val url = server.url("/xml/device_description.xml").toString()
    val device = SonosDiscoverer(OkHttpClient(), scannerReturning()).fetchDevice(url)!!
    assertEquals("uuid:RINCON_949F3EC213E001400", device.udn)
    assertEquals("Kitchen", device.name)
  }

  @Test
  fun `fetchDevice returns null on an http error`() {
    server.enqueue(MockResponse().setResponseCode(404))
    val url = server.url("/missing.xml").toString()
    assertNull(SonosDiscoverer(OkHttpClient(), scannerReturning()).fetchDevice(url))
  }

  @Test
  fun `discover scans, fetches, parses, and dedups by udn`() {
    // Serve the Sonos description for the xml path; 404 for anything else (a non-Sonos location).
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          if (request.path?.endsWith("device_description.xml") == true) {
            MockResponse().setResponseCode(200).setBody(sonosXml)
          } else {
            MockResponse().setResponseCode(404)
          }
      }

    val sonosLocation = server.url("/xml/device_description.xml").toString()
    val otherLocation = server.url("/other/desc.xml").toString()
    val scanner =
      scannerReturning(
        ssdp(sonosLocation, "uuid:RINCON_949F3EC213E001400::x"),
        // Duplicate SSDP response for the same device (Sonos sends several) — must dedup.
        ssdp(sonosLocation, "uuid:RINCON_949F3EC213E001400::y"),
        // A non-Sonos device that fails to fetch — must be dropped.
        ssdp(otherLocation, "uuid:SOMETHING_ELSE::z"),
      )

    val devices = SonosDiscoverer(OkHttpClient(), scanner).discover()
    assertEquals(1, devices.size)
    assertEquals("uuid:RINCON_949F3EC213E001400", devices[0].udn)
  }
}
