package com.audiobrowser.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * An [X509TrustManager] that retries validation after filling in missing intermediate certificates
 * via the leaf's Authority Information Access (AIA) "CA Issuers" pointer.
 *
 * Wraps a [delegate] (typically the platform default trust manager). On the happy path the delegate
 * accepts the server-presented chain on the first try and this adds nothing. Only when the delegate
 * rejects the chain *and* the chain is genuinely missing an intermediate does it attempt AIA
 * chasing — and the *completed* chain is still validated by the same delegate against the system
 * trust anchors, so trust can never be weakened: an untrusted root still fails. See
 * [AiaCertChaser].
 *
 * This is the API 23 variant. On 24+ [AiaChasingExtendedTrustManager] is used instead, and must be:
 * see its documentation for why a plain [X509TrustManager] is not a safe wrapper there.
 */
class AiaChasingTrustManager(
  private val delegate: X509TrustManager,
  private val fetch: (url: String) -> List<X509Certificate>,
) : X509TrustManager {

  private val anchorSubjects: Set<X500Principal> by lazy { delegate.anchorSubjects() }

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
    withAiaRetry(chain, anchorSubjects, fetch) { delegate.checkServerTrusted(it, authType) }

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
    delegate.checkClientTrusted(chain, authType)

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

/** The subjects of a trust manager's accepted issuers; empty if it will not say. */
internal fun X509TrustManager.anchorSubjects(): Set<X500Principal> =
  runCatching { acceptedIssuers.mapTo(HashSet()) { it.subjectX500Principal } }
    .getOrElse { emptySet() }

/**
 * Runs [check], and on rejection retries it once against the AIA-completed chain.
 *
 * Nothing is fetched unless the chain is actually missing an intermediate — see
 * [AiaCertChaser.completeChain]'s `anchorSubjects`. Without that test *every* failed handshake in
 * the process, whatever the cause, would make a blocking outbound request to a host named by the
 * certificate that just failed: an expired certificate, a hostname mismatch and a
 * certificate-transparency refusal all arrive here as a [CertificateException] from a delegate that
 * is perfectly happy with the path.
 *
 * The completed chain goes through the same [check], so trust is never weakened: whatever the
 * delegate would have refused, it still refuses.
 */
internal inline fun withAiaRetry(
  chain: Array<X509Certificate>,
  anchorSubjects: Set<X500Principal>,
  noinline fetch: (url: String) -> List<X509Certificate>,
  check: (Array<X509Certificate>) -> Unit,
) {
  try {
    check(chain)
  } catch (original: CertificateException) {
    val completed = AiaCertChaser.completeChain(chain.toList(), anchorSubjects, fetch = fetch)
    // Nothing could be added — the original failure stands (don't re-check an unchanged chain).
    if (completed.size == chain.size) throw original
    try {
      check(completed.toTypedArray())
    } catch (retried: CertificateException) {
      // The chase can change which failure the caller sees (a missing intermediate can turn out
      // to hide an expiry). Keep the first one attached rather than losing it.
      retried.addSuppressed(original)
      throw retried
    }
  }
}
