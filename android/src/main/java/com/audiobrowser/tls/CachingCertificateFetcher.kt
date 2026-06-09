package com.audiobrowser.tls

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Fetches and caches issuer certificates from AIA "CA Issuers" URLs. Used by
 * [AiaChasingTrustManager] to fill in intermediates a server failed to send.
 *
 * Runs on the TLS handshake thread, so it uses short timeouts and never throws — a failed or slow
 * fetch resolves to `null` (the handshake then fails exactly as it would have without AIA chasing).
 * Successful fetches are cached by URL, so the (typically `http`) round-trip happens at most once
 * per intermediate for the process lifetime.
 */
class CachingCertificateFetcher(
  private val connectTimeoutMs: Int = 5_000,
  private val readTimeoutMs: Int = 5_000,
) {
  private val cache = ConcurrentHashMap<String, X509Certificate>()

  fun fetch(url: String): X509Certificate? {
    cache[url]?.let {
      return it
    }
    val cert = download(url)?.let { parseCertificate(it) } ?: return null
    cache[url] = cert
    return cert
  }

  private fun download(url: String): ByteArray? =
    try {
      val connection = URL(url).openConnection() as HttpURLConnection
      connection.connectTimeout = connectTimeoutMs
      connection.readTimeout = readTimeoutMs
      connection.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
      Timber.w(e, "AIA CA-issuer fetch failed: %s", url)
      null
    }

  companion object {
    /**
     * Parses the issuer certificate from the bytes a CA-Issuers URL serves. Handles the common
     * single DER certificate (e.g. Let's Encrypt) as well as PEM and PKCS#7 "certs-only" bundles,
     * returning the first certificate. Returns null if the bytes are not a parseable certificate.
     */
    fun parseCertificate(bytes: ByteArray): X509Certificate? =
      try {
        CertificateFactory.getInstance("X.509")
          .generateCertificates(ByteArrayInputStream(bytes))
          .filterIsInstance<X509Certificate>()
          .firstOrNull()
      } catch (e: Exception) {
        null
      }
  }
}
