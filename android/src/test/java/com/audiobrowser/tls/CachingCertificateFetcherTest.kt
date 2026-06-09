package com.audiobrowser.tls

import org.junit.Assert.assertEquals
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
}
