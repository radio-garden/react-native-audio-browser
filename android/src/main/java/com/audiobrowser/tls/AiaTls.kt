package com.audiobrowser.tls

import android.os.Build
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS that recovers from a server omitting its intermediate CA certificates, by fetching them from
 * the AIA "CA Issuers" URLs, as Apple's TLS stack does and Android's does not.
 *
 * Fetched certificates are only candidates: the platform default trust manager re-validates the
 * completed chain against the same system anchors, so nothing it refuses is accepted. A rejected
 * handshake can make a cleartext request, outside `NetworkSecurityPolicy`, to a URL the server's
 * certificate names, bounded by [CachingCertificateFetcher] per fetch and
 * [AiaCertChaser.MAX_CHASE_MILLIS] per chase. Installed as the process default, that applies to
 * every connection in the process.
 */
object AiaTls {

  /**
   * An [SSLSocketFactory] that chases missing intermediates. Each call has its own cache of fetched
   * intermediates, so build it once.
   */
  fun socketFactory(): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager()), null) }.socketFactory

  /**
   * The platform default trust manager with AIA chasing, with its own cache of fetched
   * intermediates. An [X509ExtendedTrustManager] on API 24+: see [AiaChasingExtendedTrustManager].
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
