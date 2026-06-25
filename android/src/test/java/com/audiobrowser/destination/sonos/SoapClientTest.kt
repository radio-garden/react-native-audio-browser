package com.audiobrowser.destination.sonos

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real HTTP round-trip for [SoapClient] using a local MockWebServer in place of a Sonos speaker. */
class SoapClientTest {

  private lateinit var server: MockWebServer
  private val client = SoapClient(OkHttpClient())

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `posts the SOAP body with a quoted SOAPACTION and returns the response body`() {
    val responseBody =
      "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
        "<s:Body><u:GetTransportInfoResponse><CurrentTransportState>PLAYING" +
        "</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>"
    server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

    val controlUrl = server.url("/MediaRenderer/AVTransport/Control").toString()
    val action = SoapEnvelopes.getTransportInfo()
    val result = client.execute(controlUrl, action)

    assertEquals(responseBody, result)
    val recorded = server.takeRequest()
    assertEquals("POST", recorded.method)
    assertEquals("/MediaRenderer/AVTransport/Control", recorded.path)
    assertEquals(
      "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"",
      recorded.getHeader("SOAPACTION"),
    )
    assertTrue(recorded.getHeader("Content-Type")!!.startsWith("text/xml"))
    assertEquals(action.body, recorded.body.readUtf8())
  }

  @Test
  fun `throws SoapException carrying the parsed fault on HTTP 500`() {
    val faultBody =
      "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
        "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
        "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
        "<errorCode>718</errorCode><errorDescription>No such object</errorDescription>" +
        "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    server.enqueue(MockResponse().setResponseCode(500).setBody(faultBody))

    val controlUrl = server.url("/ctrl").toString()
    val e =
      assertThrows(SoapException::class.java) {
        client.execute(controlUrl, SoapEnvelopes.play())
      }
    assertEquals(500, e.httpCode)
    assertEquals(718, e.fault?.errorCode)
    assertEquals("No such object", e.fault?.errorDescription)
  }
}
