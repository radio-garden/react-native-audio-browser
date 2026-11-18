package com.audiobrowser.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * An [X509TrustManager] that, when [delegate] rejects a server chain, fills in missing
 * intermediates from the AIA "CA Issuers" URLs and lets [delegate] check again. It never accepts a
 * chain [delegate] refuses.
 *
 * For API 23 only: on 24+ a plain [X509TrustManager] wrapper fails HTTPS, see
 * [AiaChasingExtendedTrustManager].
 *
 * @param fetch the candidate certificates at a CA Issuers URL, giving up by `deadlineNanos`.
 */
class AiaChasingTrustManager(
  private val delegate: X509TrustManager,
  private val fetch: (url: String, deadlineNanos: Long) -> List<X509Certificate>,
) : X509TrustManager {

  private val anchorSubjects: Set<X500Principal> by lazy { delegate.anchorSubjects() }

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
    withAiaRetry(chain, anchorSubjects, fetch) { delegate.checkServerTrusted(it, authType) }

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
    delegate.checkClientTrusted(chain, authType)

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

/** The subjects of this trust manager's accepted issuers; empty if it throws instead. */
internal fun X509TrustManager.anchorSubjects(): Set<X500Principal> =
  runCatching { acceptedIssuers.mapTo(HashSet()) { it.subjectX500Principal } }
    .getOrElse { emptySet() }

/**
 * Runs [check] on [chain]; if it throws a [CertificateException], completes the chain via AIA and
 * runs [check] once more on the result.
 *
 * A chain already ending at an issuer in [anchorSubjects] fetches nothing, so an expired leaf or a
 * hostname mismatch does not call out to a host the failed certificate names. The test is on issuer
 * names only: a chain ending at a root the device lacks is chased whatever its real failure.
 *
 * Throws the original failure when nothing could be added; otherwise the retry's failure, with the
 * original attached as suppressed.
 */
internal inline fun withAiaRetry(
  chain: Array<X509Certificate>,
  anchorSubjects: Set<X500Principal>,
  noinline fetch: (url: String, deadlineNanos: Long) -> List<X509Certificate>,
  check: (Array<X509Certificate>) -> Unit,
) {
  try {
    check(chain)
  } catch (original: CertificateException) {
    val completed = AiaCertChaser.completeChain(chain.toList(), anchorSubjects, fetch = fetch)
    if (completed.size == chain.size) throw original
    try {
      check(completed.toTypedArray())
    } catch (retried: CertificateException) {
      retried.addSuppressed(original)
      throw retried
    }
  }
}
