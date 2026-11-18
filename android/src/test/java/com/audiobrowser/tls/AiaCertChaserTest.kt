package com.audiobrowser.tls

import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AiaCertChaserTest {

  // Subject and issuer differ on purpose: an unstubbed mock answers null to both, which
  // completeChain reads as a self-signed root and stops on before fetching.
  private fun certWithAia(extensionValue: ByteArray): X509Certificate =
    mock(X509Certificate::class.java).also {
      `when`(it.getExtensionValue("1.3.6.1.5.5.7.1.1")).thenReturn(extensionValue)
      `when`(it.subjectX500Principal).thenReturn(X500Principal("CN=leaf"))
      `when`(it.issuerX500Principal).thenReturn(X500Principal("CN=issuer"))
    }

  private fun der(tag: Int, content: ByteArray): ByteArray {
    val length =
      when (val n = content.size) {
        in 0..0x7F -> byteArrayOf(n.toByte())
        in 0x80..0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
        in 0x100..0xFFFF -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
      }
    return byteArrayOf(tag.toByte()) + length + content
  }

  private fun aiaExtension(urls: List<String>): ByteArray {
    val caIssuersOid = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)
    val descriptions =
      urls.fold(ByteArray(0)) { acc, url ->
        acc + der(0x30, der(0x06, caIssuersOid) + der(0x86, url.toByteArray(Charsets.US_ASCII)))
      }
    return der(0x04, der(0x30, descriptions))
  }

  @Test
  fun `the AIA fixture builder round-trips through the parser`() {
    // Without this, a malformed builder would let the URL-cap tests pass by parsing nothing.
    val urls = listOf("http://ca1.example/i.crt", "http://ca2.example/i.crt")

    assertEquals(urls, AiaCertChaser.extractCaIssuerUrls(certWithAia(aiaExtension(urls))))
  }

  @Test(timeout = 5_000)
  fun `a negative long-form length does not hang the parser`() {
    // OCTET STRING { SEQUENCE { tag 0x00, length 0xFFFFFFFA = -6 } }: as an Int the length
    // rewinds the reader by the six bytes it just read, back to the start.
    val aia =
      byteArrayOf(
        0x04,
        0x08,
        0x30,
        0x06,
        0x00,
        0x84.toByte(),
        0xFF.toByte(),
        0xFF.toByte(),
        0xFF.toByte(),
        0xFA.toByte(),
      )

    assertEquals(emptyList<String>(), AiaCertChaser.extractCaIssuerUrls(certWithAia(aia)))
  }

  @Test(timeout = 5_000)
  fun `a length running past the buffer is treated as no AIA`() {
    // A SEQUENCE claiming 0x7F content bytes inside a 2-byte buffer.
    val aia = byteArrayOf(0x04, 0x04, 0x30, 0x02, 0x30, 0x7F)

    assertEquals(emptyList<String>(), AiaCertChaser.extractCaIssuerUrls(certWithAia(aia)))
  }

  @Test(timeout = 5_000)
  fun `truncated DER is treated as no AIA`() {
    val aia = byteArrayOf(0x04, 0x08, 0x30)

    assertEquals(emptyList<String>(), AiaCertChaser.extractCaIssuerUrls(certWithAia(aia)))
  }

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
    val fetch = { url: String, _: Long ->
      if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
    }

    val completed = AiaCertChaser.completeChain(listOf(leaf), fetch = fetch)

    assertEquals(listOf(leaf, r13), completed)
  }

  @Test
  fun `completeChain selects the real issuer from a multi-certificate response`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val unrelated = CertFixtures.cert("isrg-root-x1.pem")

    val completed =
      AiaCertChaser.completeChain(listOf(leaf)) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(unrelated, r13) else emptyList()
      }

    assertEquals(listOf(leaf, r13), completed)
  }

  @Test
  fun `completeChain leaves a self-signed root untouched without fetching`() {
    val root = CertFixtures.cert("isrg-root-x1.pem")
    var fetches = 0

    val completed =
      AiaCertChaser.completeChain(listOf(root)) { _, _ ->
        fetches++
        emptyList()
      }

    assertEquals(listOf(root), completed)
    assertEquals(0, fetches)
  }

  @Test
  fun `completeChain stops at a chain that already reaches a trust anchor`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val root = CertFixtures.cert("isrg-root-x1.pem")
    val fetched = mutableListOf<String>()

    // R13's AIA points at the root, so without the anchors this fetches a second time.
    val completed =
      AiaCertChaser.completeChain(
        listOf(leaf),
        anchorSubjects = setOf(root.subjectX500Principal),
      ) { url, _ ->
        fetched.add(url)
        if (url == "http://r13.i.lencr.org/") listOf(r13) else listOf(root)
      }

    assertEquals(listOf(leaf, r13), completed)
    assertEquals(listOf("http://r13.i.lencr.org/"), fetched)
  }

  @Test
  fun `completeChain fetches nothing when the presented chain is already anchored`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val root = CertFixtures.cert("isrg-root-x1.pem")
    var fetches = 0

    // A complete chain, as with any failure that is not a missing intermediate.
    val completed =
      AiaCertChaser.completeChain(
        listOf(leaf, r13),
        anchorSubjects = setOf(root.subjectX500Principal),
      ) { _, _ ->
        fetches++
        emptyList()
      }

    assertEquals(listOf(leaf, r13), completed)
    assertEquals(0, fetches)
  }

  @Test
  fun `completeChain tries only a few of a certificate's CA-Issuers URLs`() {
    val manyUrls = (1..500).map { "http://ca$it.example/issuer.crt" }
    val cert = certWithAia(aiaExtension(manyUrls))
    val fetched = mutableListOf<String>()

    AiaCertChaser.completeChain(listOf(cert)) { url, _ ->
      fetched.add(url)
      emptyList()
    }

    assertEquals(AiaCertChaser.MAX_URLS_PER_CERT, fetched.size)
  }

  @Test
  fun `duplicate CA-Issuers URLs do not consume the URL cap`() {
    val urls = List(500) { "http://ca.example/issuer.crt" } + "http://ca-b.example/issuer.crt"
    val cert = certWithAia(aiaExtension(urls))
    val fetched = mutableListOf<String>()

    AiaCertChaser.completeChain(listOf(cert)) { url, _ ->
      fetched.add(url)
      emptyList()
    }

    assertEquals(listOf("http://ca.example/issuer.crt", "http://ca-b.example/issuer.crt"), fetched)
  }

  @Test
  fun `completeChain stops fetching once its wall-clock budget is spent`() {
    val cert = certWithAia(aiaExtension((1..500).map { "http://ca$it.example/issuer.crt" }))
    var fetches = 0
    var nanos = 0L
    val deadlines = mutableListOf<Long>()

    // Each fetch takes 8s of a 20s budget, so the budget, not the URL cap, stops it at three.
    AiaCertChaser.completeChain(
      listOf(cert),
      maxUrlsPerCert = 500,
      budgetMs = 20_000,
      nowNanos = { nanos },
    ) { _, deadline ->
      fetches++
      deadlines.add(deadline)
      nanos += 8_000_000_000
      emptyList()
    }

    assertEquals(3, fetches)
    assertEquals(listOf(20_000_000_000L, 20_000_000_000L, 20_000_000_000L), deadlines)
  }

  @Test
  fun `completeChain stops when the issuer cannot be fetched`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")

    val completed = AiaCertChaser.completeChain(listOf(leaf)) { _, _ -> emptyList() }

    assertEquals(listOf(leaf), completed)
  }

  @Test
  fun `AIA-completed chain forms a valid PKIX path to the trusted root`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val root = CertFixtures.cert("isrg-root-x1.pem")
    // As of just after issuance, so the test still passes once the leaf expires.
    val date = Date(leaf.notBefore.time + 86_400_000L)

    assertThrows(CertPathValidatorException::class.java) { validatePath(listOf(leaf), root, date) }

    val completed =
      AiaCertChaser.completeChain(listOf(leaf)) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }
    validatePath(completed, root, date)
  }

  /** Validates [chain] (leaf first, anchor excluded) against [anchor] as of [date]. */
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
