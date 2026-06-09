package com.audiobrowser.tls

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import timber.log.Timber

/**
 * Fetches and caches issuer certificates from AIA "CA Issuers" URLs. Used by
 * [AiaChasingTrustManager] to fill in intermediates a server failed to send.
 *
 * Runs on the TLS handshake thread, so it uses short timeouts and never throws — a failed or slow
 * fetch resolves to an empty list (the handshake then fails exactly as it would have without AIA
 * chasing). Only non-empty results are cached, so the (typically `http`) round-trip happens at most
 * once per intermediate for the process lifetime while transient failures stay retryable.
 */
class CachingCertificateFetcher(
  private val connectTimeoutMs: Int = 5_000,
  private val readTimeoutMs: Int = 5_000,
) {
  private val cache = ConcurrentHashMap<String, List<X509Certificate>>()

  /**
   * A plain system-default socket factory for `https` AIA fetches. Without it the fetch would use
   * the process default — which, once this library's AIA factory is installed there, would recurse
   * into AIA chasing during the fetch's own handshake.
   */
  private val plainSslSocketFactory: SSLSocketFactory by lazy {
    SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
  }

  fun fetch(url: String): List<X509Certificate> {
    cache[url]?.let {
      return it
    }
    val certs = download(url)?.let { parseCertificates(it) }.orEmpty()
    if (certs.isNotEmpty()) cache[url] = certs
    return certs
  }

  private fun download(url: String): ByteArray? =
    try {
      // `as?` also restricts schemes to http/https — non-HTTP URLs (file:, ftp:) yield null.
      val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
      connection.connectTimeout = connectTimeoutMs
      connection.readTimeout = readTimeoutMs
      if (connection is HttpsURLConnection) connection.sslSocketFactory = plainSslSocketFactory
      connection.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
      Timber.w(e, "AIA CA-issuer fetch failed: %s", url)
      null
    }

  companion object {
    /**
     * Parses every certificate from the bytes a CA-Issuers URL serves — a single DER certificate
     * (e.g. Let's Encrypt), a PEM file, or a PKCS#7 "certs-only" bundle. Empty if the bytes are not
     * parseable certificates.
     */
    fun parseCertificates(bytes: ByteArray): List<X509Certificate> =
      try {
        CertificateFactory.getInstance("X.509")
          .generateCertificates(ByteArrayInputStream(bytes))
          .filterIsInstance<X509Certificate>()
      } catch (e: Exception) {
        emptyList()
      }
  }
}
