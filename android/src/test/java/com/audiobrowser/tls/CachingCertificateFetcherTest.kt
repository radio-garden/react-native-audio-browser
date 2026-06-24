package com.audiobrowser.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingCertificateFetcherTest {

  @Test
  fun `parses a DER-encoded certificate as served by the CA`() {
    val certs =
      CachingCertificateFetcher.parseCertificates(CertFixtures.bytes("letsencrypt-r13.der"))

    assertEquals(1, certs.size)
    assertEquals("CN=R13,O=Let's Encrypt,C=US", certs.first().subjectX500Principal.name)
  }

  @Test
  fun `returns empty for bytes that are not a certificate`() {
    assertTrue(
      CachingCertificateFetcher.parseCertificates("not a certificate".toByteArray()).isEmpty()
    )
  }

  // -- raw HTTP/1.0 response parsing (the http AIA fetch reads to EOF, then parses) --

  @Test
  fun `extracts status and body from a content-length response`() {
    val body = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    val raw =
      "HTTP/1.1 200 OK\r\nContent-Type: application/pkix-cert\r\nContent-Length: 4\r\n\r\n"
        .toByteArray(Charsets.ISO_8859_1) + body

    val response = CachingCertificateFetcher.parseHttpResponse(raw)!!

    assertEquals(200, response.status)
    assertNull(response.location)
    assertArrayEquals(body, response.body)
  }

  @Test
  fun `decodes a chunked body`() {
    // "DEAD" + "BEEF" split across two chunks, terminated by a zero-length chunk.
    val raw =
      ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
          "4\r\nDEAD\r\n4\r\nBEEF\r\n0\r\n\r\n")
        .toByteArray(Charsets.ISO_8859_1)

    val response = CachingCertificateFetcher.parseHttpResponse(raw)!!

    assertEquals(200, response.status)
    assertArrayEquals("DEADBEEF".toByteArray(Charsets.ISO_8859_1), response.body)
  }

  @Test
  fun `surfaces the redirect location for a 3xx response`() {
    val raw =
      "HTTP/1.1 301 Moved Permanently\r\nLocation: https://ca.example/issuer.crt\r\n\r\n"
        .toByteArray(Charsets.ISO_8859_1)

    val response = CachingCertificateFetcher.parseHttpResponse(raw)!!

    assertEquals(301, response.status)
    assertEquals("https://ca.example/issuer.crt", response.location)
  }

  @Test
  fun `returns null when the bytes are not an HTTP response`() {
    assertNull(
      CachingCertificateFetcher.parseHttpResponse("garbage without a header break".toByteArray())
    )
  }
}
