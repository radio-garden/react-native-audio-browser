package com.audiobrowser.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiaChasingTrustManagerTest {

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
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingTrustManager(delegate) {
        if (it == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    // Must not throw: the leaf-only chain is rejected, AIA fills in R13, the [leaf, R13] chain
    // passes.
    tm.checkServerTrusted(arrayOf(leaf), "RSA")

    assertEquals(listOf(1, 2), delegate.checkedChainSizes)
  }

  @Test
  fun `rethrows the original failure when no intermediate can be fetched`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 2)
    val tm = AiaChasingTrustManager(delegate) { emptyList() }

    assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "RSA") }

    // Only the original short chain was checked; no pointless re-validation of an unchanged chain.
    assertEquals(listOf(1), delegate.checkedChainSizes)
  }

  @Test
  fun `does not fetch when the delegate already trusts the presented chain`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 1)
    var fetches = 0
    val tm =
      AiaChasingTrustManager(delegate) {
        fetches++
        emptyList()
      }

    tm.checkServerTrusted(arrayOf(leaf), "RSA")

    assertEquals(0, fetches)
    assertEquals(listOf(1), delegate.checkedChainSizes)
  }
}
