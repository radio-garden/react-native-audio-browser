package com.audiobrowser.tls

import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

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
 * the two-argument form and *every* HTTPS connection in the process fails. The socket overload is
 * also where endpoint identification happens for a raw `SSLSocket` configured with
 * `endpointIdentificationAlgorithm = "HTTPS"`; skipping it skips hostname verification.
 *
 * [X509ExtendedTrustManager] is API 24+, hence the split from [AiaChasingTrustManager] — see
 * [AiaTls.trustManager], which picks by SDK level. Per-domain network security configs are
 * themselves API 24+, so the plain variant on 23 loses nothing.
 *
 * @param delegate the wrapped trust manager, normally the platform default.
 * @param fetch resolves a CA-Issuers URL to its candidate certificates, giving up no later than
 *   `deadlineNanos`.
 */
@RequiresApi(Build.VERSION_CODES.N)
class AiaChasingExtendedTrustManager(
  private val delegate: X509TrustManager,
  private val fetch: (url: String, deadlineNanos: Long) -> List<X509Certificate>,
) : X509ExtendedTrustManager() {

  /** The delegate's own extended overloads, when it has them. */
  private val extended = delegate as? X509ExtendedTrustManager

  private val anchorSubjects: Set<X500Principal> by lazy { delegate.anchorSubjects() }

  /**
   * The delegate's Android-specific hostname-aware check, if it has one.
   *
   * `android.net.http.X509TrustManagerExtensions` — how an HTTP client reaches per-host certificate
   * pinning — locates this method reflectively and refuses a trust manager that lacks it. It is not
   * part of [X509ExtendedTrustManager], and `RootTrustManager` is not a public type, so it is
   * forwarded reflectively rather than overridden.
   */
  private val hostAwareCheck: Method? by lazy {
    runCatching {
        delegate.javaClass.getMethod(
          "checkServerTrusted",
          Array<X509Certificate>::class.java,
          String::class.java,
          String::class.java,
        )
      }
      .getOrNull()
  }

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
    withAiaRetry(chain, anchorSubjects, fetch) { delegate.checkServerTrusted(it, authType) }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    socket: Socket?,
  ) =
    withAiaRetry(chain, anchorSubjects, fetch) {
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
    withAiaRetry(chain, anchorSubjects, fetch) {
      extended?.checkServerTrusted(it, authType, engine)
        ?: delegate.checkServerTrusted(it, authType)
    }

  /**
   * The signature `X509TrustManagerExtensions` looks for: validates against [host] and returns the
   * chain it accepted. Kept in step with the other overloads, AIA retry included, so installing
   * this wrapper does not cost a consumer its certificate pinning.
   */
  @Suppress("unused") // called reflectively
  fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    host: String,
  ): List<X509Certificate> {
    var accepted: List<X509Certificate> = emptyList()
    val method = hostAwareCheck
    withAiaRetry(chain, anchorSubjects, fetch) { checked ->
      // Whichever chain passed is the one to report — reporting the originally presented chain
      // would hand a pinning consumer a chain missing the intermediate that made it validate.
      // `ifEmpty` guards a delegate that reports nothing on success; the checked chain is what
      // it accepted, so that is the answer.
      accepted =
        if (method != null)
          invokeHostAware(method, checked, authType, host).ifEmpty { checked.toList() }
        else {
          // Same routing as the other overloads: an extended delegate may refuse its
          // two-argument form outright (the domain-config case), so prefer its socket overload.
          extended?.checkServerTrusted(checked, authType, null as Socket?)
            ?: delegate.checkServerTrusted(checked, authType)
          checked.toList()
        }
    }
    return accepted
  }

  @Suppress("UNCHECKED_CAST")
  private fun invokeHostAware(
    method: Method,
    chain: Array<X509Certificate>,
    authType: String,
    host: String,
  ): List<X509Certificate> =
    try {
      method.invoke(delegate, chain, authType, host) as List<X509Certificate>
    } catch (e: InvocationTargetException) {
      // Unwrap, so a rejection still reaches withAiaRetry as the CertificateException it is.
      when (val cause = e.targetException) {
        is CertificateException -> throw cause
        is RuntimeException -> throw cause
        else -> throw CertificateException(cause)
      }
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
}
