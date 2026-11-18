package com.audiobrowser.tls

import android.os.Build
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS components that recover from servers omitting intermediate CA certificates, by fetching them
 * from the AIA "CA Issuers" URL, as Apple's TLS stack does and Android's does not.
 *
 * They wrap the platform default trust manager and re-validate against the same system anchors, so
 * they never accept a chain the platform refuses. They are not free of side effects: a rejected
 * handshake can make a cleartext request to a URL the server's certificate chooses, outside the
 * app's `NetworkSecurityPolicy`. That is bounded by [CachingCertificateFetcher] per fetch and by
 * [AiaCertChaser.MAX_CHASE_MILLIS] per chase, and installing these as the process default (e.g.
 * `HttpsURLConnection.setDefaultSSLSocketFactory(AiaTls.socketFactory())`) applies it to every
 * connection in the process.
 */
object AiaTls {

  /**
   * An [SSLSocketFactory] that chases missing intermediates. Each call gets its own
   * fetched-intermediate cache, so build it once.
   */
  fun socketFactory(): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager()), null) }.socketFactory

  /**
   * The platform default trust manager with AIA chasing, with its own fetched-intermediate cache.
   *
   * On API 24+ it is an [X509ExtendedTrustManager]; see [AiaChasingExtendedTrustManager] for why a
   * plain wrapper would fail every HTTPS connection there.
   */
  fun trustManager(): X509TrustManager {
    val fetcher = CachingCertificateFetcher()
    val fetch = { url: String, deadlineNanos: Long -> fetcher.fetch(url, deadlineNanos) }
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
