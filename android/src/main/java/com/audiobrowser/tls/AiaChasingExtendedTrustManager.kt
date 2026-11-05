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
 * [AiaChasingTrustManager] as an [X509ExtendedTrustManager], for API 24+ (see
 * [AiaTls.trustManager]). Per-domain network security configs are API 24+ too, so the plain variant
 * below that loses nothing.
 *
 * A plain [X509TrustManager] wrapper is not safe here. The platform default, `RootTrustManager`,
 * throws from its two-argument `checkServerTrusted` once the app declares any `<domain-config>`:
 * > Domain specific configurations require that hostname aware
 * > checkServerTrusted(X509Certificate[], String, String) is used
 *
 * Hiding the socket and engine overloads sends conscrypt to that form and fails every HTTPS
 * connection in the process. The socket overload is also where a raw `SSLSocket` with
 * `endpointIdentificationAlgorithm = "HTTPS"` verifies the hostname.
 */
@RequiresApi(Build.VERSION_CODES.N)
class AiaChasingExtendedTrustManager(
  private val delegate: X509TrustManager,
  private val fetch: (url: String, deadlineNanos: Long) -> List<X509Certificate>,
) : X509ExtendedTrustManager() {

  private val extended = delegate as? X509ExtendedTrustManager

  private val anchorSubjects: Set<X500Principal> by lazy { delegate.anchorSubjects() }

  /**
   * The delegate's Android-only `checkServerTrusted(chain, authType, host)`. It is in no public
   * type, so it is found and forwarded by reflection.
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
      // A delegate without the extended overloads is not hostname-aware, so this loses nothing.
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
   * Validates for [host] and returns the chain accepted, which after an AIA retry includes the
   * fetched intermediates. `android.net.http.X509TrustManagerExtensions`, through which HTTP
   * clients pin certificates, looks this signature up by reflection and refuses a trust manager
   * without it.
   */
  @Suppress("unused") // called reflectively
  fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    host: String,
  ): List<X509Certificate> {
    var accepted: List<X509Certificate> = emptyList()
    val method = hostAwareCheck
    // Report the chain that passed, not the one presented: a pinning consumer needs the
    // intermediate that made it validate. `ifEmpty` covers a delegate that returns nothing.
    withAiaRetry(chain, anchorSubjects, fetch) { checked ->
      accepted =
        if (method != null)
          invokeHostAware(method, checked, authType, host).ifEmpty { checked.toList() }
        else {
          // An extended delegate may refuse its two-argument form (the `<domain-config>` case).
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
      // Unwrapped, so withAiaRetry sees a rejection as the CertificateException it is.
      when (val cause = e.targetException) {
        is CertificateException -> throw cause
        is RuntimeException -> throw cause
        else -> throw CertificateException(cause)
      }
    }

  // Client certificates are the app's own: nothing to chase.

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
