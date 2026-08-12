package com.audiobrowser.tls

import android.os.Build
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds TLS components that recover from servers which omit intermediate CA certificates, by
 * following the leaf's Authority Information Access (AIA) "CA Issuers" pointer — the behaviour
 * Apple's Secure Transport has by default but Android's lacks.
 *
 * The returned components are side-effect-free: they wrap the platform default trust manager and
 * only ever *add* a missing intermediate before re-validating against the same system trust
 * anchors, so they cannot weaken trust. Installing them as a process default (e.g.
 * `HttpsURLConnection.setDefaultSSLSocketFactory(AiaTls.socketFactory())`) is the caller's
 * decision.
 */
object AiaTls {

  /**
   * An [SSLSocketFactory] whose handshake validation chases missing intermediates via AIA. Intended
   * to be built once (e.g. installed as the process default at startup); each call gets its own
   * fetched-intermediate cache.
   */
  fun socketFactory(): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager()), null) }.socketFactory

  /**
   * An [X509TrustManager] wrapping the platform default with AIA intermediate chasing.
   *
   * On API 24+ this is an [X509ExtendedTrustManager]. That is not cosmetic: the platform's default
   * trust manager rejects the two-argument `checkServerTrusted` outright once the app declares any
   * `<domain-config>`, so a plain wrapper would break every HTTPS connection in the process. See
   * [AiaChasingExtendedTrustManager].
   */
  fun trustManager(): X509TrustManager {
    val fetcher = CachingCertificateFetcher()
    val fetch = { url: String -> fetcher.fetch(url) }
    val system = systemTrustManager()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      AiaChasingExtendedTrustManager(system, fetch)
    } else {
      AiaChasingTrustManager(system, fetch)
    }
  }

  private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
  }
}
