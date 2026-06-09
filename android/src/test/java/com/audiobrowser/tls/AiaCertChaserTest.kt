package com.audiobrowser.tls

import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiaCertChaserTest {

  private fun loadCert(resource: String): X509Certificate {
    val stream =
      checkNotNull(javaClass.getResourceAsStream("/certs/$resource")) {
        "Missing test fixture: /certs/$resource"
      }
    return stream.use {
      CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
  }

  @Test
  fun `extracts CA Issuers URL from real leaf certificate AIA extension`() {
    val leaf = loadCert("stationplaylist-leaf.pem")

    val urls = AiaCertChaser.extractCaIssuerUrls(leaf)

    assertEquals(listOf("http://r13.i.lencr.org/"), urls)
  }

  @Test
  fun `completeChain appends fetched intermediate when server sends leaf only`() {
    val leaf = loadCert("stationplaylist-leaf.pem")
    val r13 = loadCert("letsencrypt-r13.pem")
    // Fetcher knows only the R13 URL (the leaf's AIA pointer); returns null for the root URL.
    val fetch = { url: String -> if (url == "http://r13.i.lencr.org/") r13 else null }

    val completed = AiaCertChaser.completeChain(listOf(leaf), fetch = fetch)

    assertEquals(listOf(leaf, r13), completed)
  }

  @Test
  fun `completeChain leaves a self-signed root untouched without fetching`() {
    val root = loadCert("isrg-root-x1.pem")
    var fetches = 0

    val completed =
      AiaCertChaser.completeChain(listOf(root)) {
        fetches++
        null
      }

    assertEquals(listOf(root), completed)
    assertEquals(0, fetches)
  }

  @Test
  fun `completeChain stops when the issuer cannot be fetched`() {
    val leaf = loadCert("stationplaylist-leaf.pem")

    val completed = AiaCertChaser.completeChain(listOf(leaf)) { null }

    assertEquals(listOf(leaf), completed)
  }

  @Test
  fun `AIA-completed chain forms a valid PKIX path to the trusted root`() {
    val leaf = loadCert("stationplaylist-leaf.pem")
    val r13 = loadCert("letsencrypt-r13.pem")
    val root = loadCert("isrg-root-x1.pem")
    // Validate as of just after issuance so the fixtures never rot when the leaf expires.
    val date = Date(leaf.notBefore.time + 86_400_000L)

    // Reproduces the Android bug: the server-sent leaf alone has no path to the trusted root.
    assertThrows(CertPathValidatorException::class.java) { validatePath(listOf(leaf), root, date) }

    // After AIA chasing splices in R13, the chain validates to the trusted root.
    val completed =
      AiaCertChaser.completeChain(listOf(leaf)) {
        if (it == "http://r13.i.lencr.org/") r13 else null
      }
    validatePath(completed, root, date) // must not throw
  }

  /** Validates [chain] (leaf-first, excluding the anchor) against [anchor] as of [date]. */
  private fun validatePath(chain: List<X509Certificate>, anchor: X509Certificate, date: Date) {
    val path = CertificateFactory.getInstance("X.509").generateCertPath(chain)
    val params =
      PKIXParameters(setOf(TrustAnchor(anchor, null))).apply {
        isRevocationEnabled = false
        this.date = date
      }
    CertPathValidator.getInstance("PKIX").validate(path, params)
  }
}
