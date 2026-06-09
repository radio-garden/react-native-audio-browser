package com.audiobrowser.tls

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiaChasingTrustManagerTest {

  private fun loadCert(resource: String): X509Certificate {
    val stream =
      checkNotNull(javaClass.getResourceAsStream("/certs/$resource")) {
        "Missing test fixture: /certs/$resource"
      }
    return stream.use {
      CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
  }

  /** Records every chain it is asked to validate; trusts only chains of [trustsChainOfSize]. */
  private class FakeDelegate(private val trustsChainOfSize: Int) : X509TrustManager {
    val checkedChainSizes = mutableListOf<Int>()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
      checkedChainSizes.add(chain.size)
      if (chain.size != trustsChainOfSize) {
        throw CertificateException("Trust anchor for certification path not found.")
      }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  @Test
  fun `re-validates the AIA-completed chain when the delegate rejects the leaf-only chain`() {
    val leaf = loadCert("stationplaylist-leaf.pem")
    val r13 = loadCert("letsencrypt-r13.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 2)
    val tm = AiaChasingTrustManager(delegate) { if (it == "http://r13.i.lencr.org/") r13 else null }

    // Must not throw: the leaf-only chain is rejected, AIA fills in R13, the [leaf, R13] chain
    // passes.
    tm.checkServerTrusted(arrayOf(leaf), "RSA")

    assertEquals(listOf(1, 2), delegate.checkedChainSizes)
  }

  @Test
  fun `rethrows the original failure when no intermediate can be fetched`() {
    val leaf = loadCert("stationplaylist-leaf.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 2)
    val tm = AiaChasingTrustManager(delegate) { null }

    assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "RSA") }

    // Only the original short chain was checked; no pointless re-validation of an unchanged chain.
    assertEquals(listOf(1), delegate.checkedChainSizes)
  }

  @Test
  fun `does not fetch when the delegate already trusts the presented chain`() {
    val leaf = loadCert("stationplaylist-leaf.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 1)
    var fetches = 0
    val tm =
      AiaChasingTrustManager(delegate) {
        fetches++
        null
      }

    tm.checkServerTrusted(arrayOf(leaf), "RSA")

    assertEquals(0, fetches)
    assertEquals(listOf(1), delegate.checkedChainSizes)
  }
}
