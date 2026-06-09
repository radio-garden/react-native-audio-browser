package com.audiobrowser.tls

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Loads certificate fixtures from `src/test/resources/certs`. */
object CertFixtures {
  fun bytes(resource: String): ByteArray =
    checkNotNull(CertFixtures::class.java.getResourceAsStream("/certs/$resource")) {
        "Missing test fixture: /certs/$resource"
      }
      .use { it.readBytes() }

  fun cert(resource: String): X509Certificate =
    bytes(resource).inputStream().use {
      CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
}
