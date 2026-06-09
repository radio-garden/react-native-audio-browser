package com.audiobrowser.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachingCertificateFetcherTest {

  private fun fixtureBytes(resource: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/certs/$resource")) {
        "Missing test fixture: /certs/$resource"
      }
      .use { it.readBytes() }

  @Test
  fun `parses a DER-encoded certificate as served by the CA`() {
    val cert = CachingCertificateFetcher.parseCertificate(fixtureBytes("letsencrypt-r13.der"))

    assertEquals("CN=R13,O=Let's Encrypt,C=US", cert?.subjectX500Principal?.name)
  }

  @Test
  fun `returns null for bytes that are not a certificate`() {
    assertNull(CachingCertificateFetcher.parseCertificate("not a certificate".toByteArray()))
  }
}
