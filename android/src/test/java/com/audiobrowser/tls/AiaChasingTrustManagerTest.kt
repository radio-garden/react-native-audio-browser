package com.audiobrowser.tls

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
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

  // -- the API 24+ variant, which is what the platform actually calls into --

  /**
   * Stands in for Android's `RootTrustManager` under a per-domain network security config: the
   * hostname-aware overloads work, and the two-argument one refuses to answer at all.
   */
  private class FakeDomainConfigDelegate(private val trustsChainOfSize: Int) :
    X509ExtendedTrustManager() {
    val hostnameAwareChecks = mutableListOf<Int>()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
      throw CertificateException(
        "Domain specific configurations require that hostname aware " +
          "checkServerTrusted(X509Certificate[], String, String) is used"
      )

    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      socket: Socket?,
    ) {
      hostnameAwareChecks.add(chain.size)
      if (chain.size != trustsChainOfSize) {
        throw CertificateException("Trust anchor for certification path not found.")
      }
    }

    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      engine: SSLEngine?,
    ) = checkServerTrusted(chain, authType, null as Socket?)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      socket: Socket?,
    ) = Unit

    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      engine: SSLEngine?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  @Test
  fun `forwards to the hostname-aware overload the platform requires`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = FakeDomainConfigDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingExtendedTrustManager(delegate) {
        if (it == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    // The socket overload must reach the delegate's socket overload. Routing it through the
    // two-argument form instead would throw the domain-config refusal, which is what a plain
    // X509TrustManager wrapper causes for every connection in the process.
    tm.checkServerTrusted(arrayOf(leaf), "RSA", null as Socket?)

    assertEquals(listOf(1, 2), delegate.hostnameAwareChecks)
  }

  @Test
  fun `the engine overload is hostname-aware too`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val delegate = FakeDomainConfigDelegate(trustsChainOfSize = 1)

    AiaChasingExtendedTrustManager(delegate) { emptyList() }
      .checkServerTrusted(arrayOf(leaf), "RSA", null as SSLEngine?)

    assertEquals(listOf(1), delegate.hostnameAwareChecks)
  }

  @Test
  fun `a delegate without extended overloads still works`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingExtendedTrustManager(delegate) {
        if (it == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    tm.checkServerTrusted(arrayOf(leaf), "RSA", null as Socket?)

    assertEquals(listOf(1, 2), delegate.checkedChainSizes)
  }

  @Test
  fun `the extended variant does not weaken trust`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    // Trusts nothing, whatever the chain.
    val delegate = FakeDomainConfigDelegate(trustsChainOfSize = -1)
    val tm = AiaChasingExtendedTrustManager(delegate) { listOf(r13) }

    assertThrows(CertificateException::class.java) {
      tm.checkServerTrusted(arrayOf(leaf), "RSA", null as Socket?)
    }

    // The completed chain was offered to the delegate and refused, rather than assumed good.
    assertEquals(listOf(1, 2), delegate.hostnameAwareChecks)
  }
}
