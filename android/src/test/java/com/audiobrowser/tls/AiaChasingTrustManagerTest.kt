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

  /** Records the size of every chain it checks; trusts only chains of [trustsChainOfSize]. */
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
      AiaChasingTrustManager(delegate) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    tm.checkServerTrusted(arrayOf(leaf), "RSA")

    assertEquals(listOf(1, 2), delegate.checkedChainSizes)
  }

  @Test
  fun `rethrows the original failure when no intermediate can be fetched`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 2)
    val tm = AiaChasingTrustManager(delegate) { _, _ -> emptyList() }

    assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "RSA") }

    // The unchanged chain is not checked a second time.
    assertEquals(listOf(1), delegate.checkedChainSizes)
  }

  @Test
  fun `does not fetch when the delegate already trusts the presented chain`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 1)
    var fetches = 0
    val tm =
      AiaChasingTrustManager(delegate) { _, _ ->
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
   * hostname-aware overloads work, and the two-argument one always throws.
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
  fun `a complete chain rejected for some other reason is not chased`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val root = CertFixtures.cert("isrg-root-x1.pem")
    // Rejects a chain that lacks nothing (as for an expired leaf): r13's issuer is an anchor.
    val delegate =
      object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
          throw CertificateException("certificate expired")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(root)
      }
    var fetches = 0
    val tm =
      AiaChasingTrustManager(delegate) { _, _ ->
        fetches++
        emptyList()
      }

    assertThrows(CertificateException::class.java) {
      tm.checkServerTrusted(arrayOf(leaf, r13), "RSA")
    }

    assertEquals(0, fetches)
  }

  @Test
  fun `the original failure is kept when the completed chain fails too`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    // Trusts no chain, so the retry fails too.
    val delegate = FakeDelegate(trustsChainOfSize = -1)
    val tm = AiaChasingTrustManager(delegate) { _, _ -> listOf(r13) }

    val thrown =
      assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "RSA") }

    assertEquals(1, thrown.suppressed.size)
  }

  @Test
  fun `client checks are forwarded, not routed through the server path`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    var clientChecks = 0
    val delegate =
      object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
          throw CertificateException("server path must not be used for a client check")

        override fun checkServerTrusted(
          chain: Array<X509Certificate>,
          authType: String,
          socket: Socket?,
        ) = throw CertificateException("server path must not be used for a client check")

        override fun checkServerTrusted(
          chain: Array<X509Certificate>,
          authType: String,
          engine: SSLEngine?,
        ) = throw CertificateException("server path must not be used for a client check")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
          clientChecks++
        }

        override fun checkClientTrusted(
          chain: Array<X509Certificate>,
          authType: String,
          socket: Socket?,
        ) {
          clientChecks++
        }

        override fun checkClientTrusted(
          chain: Array<X509Certificate>,
          authType: String,
          engine: SSLEngine?,
        ) {
          clientChecks++
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }
    val tm = AiaChasingExtendedTrustManager(delegate) { _, _ -> emptyList() }

    tm.checkClientTrusted(arrayOf(leaf), "RSA")
    tm.checkClientTrusted(arrayOf(leaf), "RSA", null as Socket?)
    tm.checkClientTrusted(arrayOf(leaf), "RSA", null as SSLEngine?)

    assertEquals(3, clientChecks)
  }

  /** Carries the Android-specific hostname-aware method, as `RootTrustManager` does. */
  @Suppress("unused")
  private class HostAwareDelegate(private val trustsChainOfSize: Int) : X509TrustManager {
    val hosts = mutableListOf<String>()

    fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      host: String,
    ): List<X509Certificate> {
      hosts.add(host)
      if (chain.size != trustsChainOfSize) {
        throw CertificateException("Trust anchor for certification path not found.")
      }
      return chain.toList()
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
      throw CertificateException("the host-aware overload should have been used")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  @Test
  fun `the X509TrustManagerExtensions signature is present and forwards the host`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = HostAwareDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingExtendedTrustManager(delegate) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    // X509TrustManagerExtensions looks this up reflectively and needs it to return a List.
    val method =
      tm.javaClass.getMethod(
        "checkServerTrusted",
        Array<X509Certificate>::class.java,
        String::class.java,
        String::class.java,
      )

    val accepted = tm.checkServerTrusted(arrayOf(leaf), "RSA", "ca.example")

    assertEquals(listOf(leaf, r13), accepted)
    // Once for the presented chain, once for the completed one.
    assertEquals(listOf("ca.example", "ca.example"), delegate.hosts)
    assertEquals(List::class.java, method.returnType)
  }

  @Test
  fun `the host-aware signature falls back to the extended overloads when the delegate lacks it`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = FakeDomainConfigDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingExtendedTrustManager(delegate) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    val accepted = tm.checkServerTrusted(arrayOf(leaf), "RSA", "ca.example")

    assertEquals(listOf(leaf, r13), accepted)
    assertEquals(listOf(1, 2), delegate.hostnameAwareChecks)
  }

  /** Validates fine but reports an empty accepted chain. */
  @Suppress("unused")
  private class SilentHostAwareDelegate(private val trustsChainOfSize: Int) : X509TrustManager {
    fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      host: String,
    ): List<X509Certificate> {
      if (chain.size != trustsChainOfSize) {
        throw CertificateException("Trust anchor for certification path not found.")
      }
      return emptyList()
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
      throw CertificateException("the host-aware overload should have been used")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  @Test
  fun `a delegate reporting an empty accepted chain still yields the chain that validated`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = SilentHostAwareDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingExtendedTrustManager(delegate) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    val accepted = tm.checkServerTrusted(arrayOf(leaf), "RSA", "ca.example")

    assertEquals(listOf(leaf, r13), accepted)
  }

  @Test
  fun `forwards to the hostname-aware overload the platform requires`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = FakeDomainConfigDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingExtendedTrustManager(delegate) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    tm.checkServerTrusted(arrayOf(leaf), "RSA", null as Socket?)

    assertEquals(listOf(1, 2), delegate.hostnameAwareChecks)
  }

  @Test
  fun `the engine overload is hostname-aware too`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val delegate = FakeDomainConfigDelegate(trustsChainOfSize = 1)

    AiaChasingExtendedTrustManager(delegate) { _, _ -> emptyList() }
      .checkServerTrusted(arrayOf(leaf), "RSA", null as SSLEngine?)

    assertEquals(listOf(1), delegate.hostnameAwareChecks)
  }

  @Test
  fun `a delegate without extended overloads still works`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    val delegate = FakeDelegate(trustsChainOfSize = 2)
    val tm =
      AiaChasingExtendedTrustManager(delegate) { url, _ ->
        if (url == "http://r13.i.lencr.org/") listOf(r13) else emptyList()
      }

    tm.checkServerTrusted(arrayOf(leaf), "RSA", null as Socket?)

    assertEquals(listOf(1, 2), delegate.checkedChainSizes)
  }

  @Test
  fun `the extended variant does not weaken trust`() {
    val leaf = CertFixtures.cert("stationplaylist-leaf.pem")
    val r13 = CertFixtures.cert("letsencrypt-r13.pem")
    // Trusts no chain.
    val delegate = FakeDomainConfigDelegate(trustsChainOfSize = -1)
    val tm = AiaChasingExtendedTrustManager(delegate) { _, _ -> listOf(r13) }

    assertThrows(CertificateException::class.java) {
      tm.checkServerTrusted(arrayOf(leaf), "RSA", null as Socket?)
    }

    assertEquals(listOf(1, 2), delegate.hostnameAwareChecks)
  }
}
