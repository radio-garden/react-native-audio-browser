package com.audiobrowser.tls

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * The [AiaChasingTrustManager] behaviour, as an [X509ExtendedTrustManager].
 *
 * The socket- and engine-aware overloads are the ones the platform actually calls, and they are not
 * optional. Android's default trust manager is a `RootTrustManager`, which extends
 * [X509ExtendedTrustManager] and whose two-argument `checkServerTrusted` throws unconditionally
 * once the app declares any `<domain-config>` in its network security config:
 * > Domain specific configurations require that hostname aware
 * > checkServerTrusted(X509Certificate[], String, String) is used
 *
 * Wrapping it in a plain [X509TrustManager] hides those overloads, so conscrypt falls through to
 * the two-argument form and *every* HTTPS connection in the process fails — and, because the
 * wrapper treats a [CertificateException] as its cue to chase AIA, fails slowly. The socket
 * overload is also where endpoint identification happens for a raw `SSLSocket` configured with
 * `endpointIdentificationAlgorithm = "HTTPS"`; skipping it skips hostname verification.
 *
 * [X509ExtendedTrustManager] is API 24+, hence the split from [AiaChasingTrustManager] — see
 * [AiaTls.trustManager], which picks by SDK level. Per-domain network security configs are
 * themselves API 24+, so the plain variant on 23 loses nothing.
 *
 * @param delegate the wrapped trust manager, normally the platform default.
 * @param fetch resolves a CA-Issuers URL to its candidate certificates.
 */
class AiaChasingExtendedTrustManager(
  private val delegate: X509TrustManager,
  private val fetch: (url: String) -> List<X509Certificate>,
) : X509ExtendedTrustManager() {

  /** The delegate's own extended overloads, when it has them. */
  private val extended = delegate as? X509ExtendedTrustManager

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
    withAiaRetry(chain) { delegate.checkServerTrusted(it, authType) }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    socket: Socket?,
  ) =
    withAiaRetry(chain) {
      // A delegate without the extended overloads cannot be hostname-aware in the first place,
      // so falling back to its two-argument form loses nothing it could have offered.
      extended?.checkServerTrusted(it, authType, socket)
        ?: delegate.checkServerTrusted(it, authType)
    }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    engine: SSLEngine?,
  ) =
    withAiaRetry(chain) {
      extended?.checkServerTrusted(it, authType, engine)
        ?: delegate.checkServerTrusted(it, authType)
    }

  // Client certificates are the app's own; there is nothing to chase.

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
    delegate.checkClientTrusted(chain, authType)

  override fun checkClientTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    socket: Socket?,
  ) {
    extended?.checkClientTrusted(chain, authType, socket)
      ?: delegate.checkClientTrusted(chain, authType)
  }

  override fun checkClientTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    engine: SSLEngine?,
  ) {
    extended?.checkClientTrusted(chain, authType, engine)
      ?: delegate.checkClientTrusted(chain, authType)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

  /**
   * Runs [check], and on rejection retries it once against the AIA-completed chain. The completed
   * chain goes through the same [check], so trust is never weakened: whatever the delegate would
   * have refused, it still refuses.
   */
  private inline fun withAiaRetry(
    chain: Array<X509Certificate>,
    check: (Array<X509Certificate>) -> Unit,
  ) {
    try {
      check(chain)
    } catch (original: CertificateException) {
      val completed = AiaCertChaser.completeChain(chain.toList(), fetch = fetch)
      // Nothing could be added — the original failure stands (don't re-check an unchanged chain).
      if (completed.size == chain.size) throw original
      check(completed.toTypedArray())
    }
  }
}
