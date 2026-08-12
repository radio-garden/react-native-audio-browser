package com.audiobrowser.tls

import java.io.ByteArrayInputStream
import java.io.InputStream
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

  // -- response cap (the AIA URL, so the host serving it, comes from the presented cert) --

  // -- URL validation (the AIA URL is attacker-supplied text from the presented cert) --

  @Test
  fun `accepts the URL form CAs actually serve`() {
    assertTrue(CachingCertificateFetcher.isSafeUrl("http://r13.i.lencr.org/"))
    assertTrue(CachingCertificateFetcher.isSafeUrl("http://ca.example:8080/issuer.crt?v=2"))
  }

  @Test
  fun `rejects a URL carrying CRLF, which would inject request lines`() {
    // java.net.URL keeps the CRLF in `file`, and the cleartext fetch writes `file` straight
    // into "GET $path HTTP/1.0\r\n" on a raw socket.
    assertTrue(!CachingCertificateFetcher.isSafeUrl("http://127.0.0.1:6379/\r\nSET foo bar\r\n"))
    assertTrue(!CachingCertificateFetcher.isSafeUrl("http://ca.example/a\nX-Injected: 1"))
  }

  @Test
  fun `rejects a URL carrying a space, which would split the request line`() {
    assertTrue(!CachingCertificateFetcher.isSafeUrl("http://ca.example/a b"))
  }

  @Test
  fun `reads a response that fits within the cap`() {
    val body = ByteArray(64) { it.toByte() }

    assertArrayEquals(
      body,
      CachingCertificateFetcher.readCapped(ByteArrayInputStream(body), limit = 128),
    )
  }

  @Test
  fun `returns null for a response that exceeds the cap`() {
    val body = ByteArray(129)

    assertNull(CachingCertificateFetcher.readCapped(ByteArrayInputStream(body), limit = 128))
  }

  @Test
  fun `a stream that never ends does not read without bound`() {
    // Endless: read() always fills the buffer and never signals EOF, the shape a slow-drip
    // server takes — the per-read timeout never fires because every read succeeds.
    val endless =
      object : InputStream() {
        override fun read() = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int = len
      }

    assertNull(CachingCertificateFetcher.readCapped(endless, limit = 1 shl 16))
  }

  @Test
  fun `a slow drip is cut off by the deadline, not left to exhaust the byte cap`() {
    // One byte per read, each one landing just inside the socket's read timeout: the byte cap
    // alone would let this run for a million reads. The clock advances 1s per read, so the
    // 10s budget must end it after ~10 — far short of the 1 MiB cap.
    var reads = 0
    val drip =
      object : InputStream() {
        override fun read() = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          reads++
          return 1
        }
      }
    var nanos = 0L

    val result =
      CachingCertificateFetcher.readCapped(drip, budgetMs = 10_000) {
        nanos += 1_000_000_000
        nanos
      }

    assertNull(result)
    assertTrue("gave up after $reads reads", reads < 20)
  }
}
