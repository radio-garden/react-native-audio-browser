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

  @Test
  fun `extracts CA Issuers URL from real leaf certificate AIA extension`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")

    val urls = AiaCertChaser.extractCaIssuerUrls(leaf)

    assertEquals(listOf("http://r13.i.lencr.org/"), urls)
  }

  @Test
  fun `completeChain appends fetched intermediate when server sends leaf only`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    // Fetcher knows only the R13 URL (the leaf's AIA pointer); empty for the root URL.
    val fetch = { url: String ->
      if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
    }

    val completed = AiaCertChaser.completeChain(listOf(leaf), fetch = fetch)

    assertEquals(listOf(leaf, r13), completed)
  }

  @Test
  fun `completeChain selects the real issuer from a multi-certificate response`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val unrelated = CertFixtures.cert("isrg-root-x1.pem") // not the leaf's issuer

    // A PKCS#7-style bundle where the genuine issuer is not first must still be selected.
    val completed =
      AiaCertChaser.completeChain(listOf(leaf)) {
        if (it == "http://r13.i.lencr.org/") listOf(unrelated, r13) else emptyList()
      }

    assertEquals(listOf(leaf, r13), completed)
  }

  @Test
  fun `completeChain leaves a self-signed root untouched without fetching`() {
    val root = CertFixtures.cert("isrg-root-x1.pem")
    var fetches = 0

    val completed =
      AiaCertChaser.completeChain(listOf(root)) {
        fetches++
        emptyList()
      }

    assertEquals(listOf(root), completed)
    assertEquals(0, fetches)
  }

  @Test
  fun `completeChain stops when the issuer cannot be fetched`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")

    val completed = AiaCertChaser.completeChain(listOf(leaf)) { emptyList() }

    assertEquals(listOf(leaf), completed)
  }

  @Test
  fun `AIA-completed chain forms a valid PKIX path to the trusted root`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val root = CertFixtures.cert("isrg-root-x1.pem")
    // Validate as of just after issuance so the fixtures never rot when the leaf expires.
    val date = Date(leaf.notBefore.time + 86_400_000L)

    // Reproduces the Android bug: the server-sent leaf alone has no path to the trusted root.
    assertThrows(CertPathValidatorException::class.java) { validatePath(listOf(leaf), root, date) }

    // After AIA chasing splices in R13, the chain validates to the trusted root.
    val completed =
      AiaCertChaser.completeChain(listOf(leaf)) {
        if (it == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
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
