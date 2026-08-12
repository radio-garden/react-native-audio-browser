package com.audiobrowser.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * An [X509TrustManager] that retries validation after filling in missing intermediate certificates
 * via the leaf's Authority Information Access (AIA) "CA Issuers" pointer.
 *
 * Wraps a [delegate] (typically the platform default trust manager). On the happy path the delegate
 * accepts the server-presented chain on the first try and this adds nothing. Only when the delegate
 * rejects the chain does it attempt AIA chasing — and the *completed* chain is still validated by
 * the same delegate against the system trust anchors, so trust can never be weakened: an untrusted
 * root still fails. See [AiaCertChaser].
 */
class AiaChasingTrustManager(
  private val delegate: X509TrustManager,
  private val fetch: (url: String) -> List<X509Certificate>,
) : X509TrustManager {

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
    try {
      delegate.checkServerTrusted(chain, authType)
    } catch (original: CertificateException) {
      val completed = AiaCertChaser.completeChain(chain.toList(), fetch = fetch)
      // Nothing could be added — the original failure stands (don't re-check an unchanged chain).
      if (completed.size == chain.size) throw original
      delegate.checkServerTrusted(completed.toTypedArray(), authType)
    }
  }

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
    delegate.checkClientTrusted(chain, authType)

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
