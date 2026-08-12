package com.audiobrowser.tls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
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
 *
 * Responses are capped at [MAX_RESPONSE_BYTES]. The URL comes from a server-presented certificate,
 * so the host, port and path are chosen by whoever we are failing to validate; the read timeout is
 * per-read and would not stop a slow drip of unbounded length. A certificate is a few kilobytes and
 * even a PKCS#7 bundle a few dozen, so the cap is far above anything legitimate.
 *
 * `http` fetches go over a raw [Socket] rather than `HttpURLConnection`. CA "CA Issuers" URLs are
 * virtually always plain `http` (the CA/Browser Forum Baseline Requirements mandate it, to avoid a
 * chicken-and-egg TLS dependency when fetching the cert needed to complete a TLS chain), but
 * Android blocks cleartext `HttpURLConnection` traffic by default on apps targeting API 28+. A raw
 * socket is not subject to `NetworkSecurityPolicy`, so the fetch succeeds regardless of the host
 * app's cleartext setting — safely, because the fetched certificate is still cryptographically
 * verified to be the real issuer before [AiaChasingTrustManager] trusts it.
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

  private fun download(url: String, redirectsLeft: Int = MAX_REDIRECTS): ByteArray? =
    try {
      require(isSafeUrl(url)) { "AIA URL contains characters that are not valid in a URL" }
      val parsed = URL(url)
      when (parsed.protocol) {
        "http" -> downloadCleartext(parsed, redirectsLeft)
        "https" -> downloadHttps(parsed)
        else -> null // restrict to http/https — file:, ftp: etc. are never valid CA-issuer sources
      }
    } catch (e: Exception) {
      Timber.w(e, "AIA CA-issuer fetch failed: %s", url)
      null
    }

  /**
   * `https` AIA fetch over the platform HTTP stack. `https` is never subject to Android's cleartext
   * policy, so `HttpsURLConnection` is fine here; the plain socket factory keeps the fetch's own
   * handshake from recursing into AIA chasing.
   */
  private fun downloadHttps(url: URL): ByteArray? {
    val connection = url.openConnection() as? HttpsURLConnection ?: return null
    connection.connectTimeout = connectTimeoutMs
    connection.readTimeout = readTimeoutMs
    connection.sslSocketFactory = plainSslSocketFactory
    return connection.inputStream.use { readCapped(it) }
  }

  /**
   * `http` AIA fetch over a raw socket — see the class doc for why this bypasses
   * `HttpURLConnection`.
   */
  private fun downloadCleartext(url: URL, redirectsLeft: Int): ByteArray? {
    val host = url.host
    val port = if (url.port != -1) url.port else 80
    val path = url.file.ifEmpty { "/" } // URL.file is path + query
    val raw =
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs
        // HTTP/1.0 + `Connection: close` so the server closes after the body and we can read to EOF
        // without parsing Content-Length.
        val request =
          "GET $path HTTP/1.0\r\n" +
            "Host: $host\r\n" +
            "Connection: close\r\n" +
            "User-Agent: react-native-audio-browser\r\n" +
            "Accept: */*\r\n\r\n"
        socket.getOutputStream().apply {
          write(request.toByteArray(Charsets.ISO_8859_1))
          flush()
        }
        socket.getInputStream().use { readCapped(it) }
      } ?: return null
    val response = parseHttpResponse(raw) ?: return null
    return when {
      response.status in 200..299 -> response.body
      response.status in 300..399 && response.location != null && redirectsLeft > 0 ->
        // Follow CA-issuer redirects (rare). A redirect to `https` falls through to the https path.
        download(URL(url, response.location).toString(), redirectsLeft - 1)
      else -> null
    }
  }

  companion object {
    private const val MAX_REDIRECTS = 5

    /**
     * Whether [url] is free of characters that have no business in a URL.
     *
     * The cleartext fetch writes the path into a request line on a raw socket, and the URL is an
     * IA5String copied verbatim out of a certificate a hostile server presented. `java.net.URL`
     * preserves control characters — `URL("http://host:6379/\r\nSET foo bar")` parses to that host
     * and port with the CRLF intact in `file` — so without this check a certificate could inject
     * request lines of its own, to any host and port the device can reach. Space is excluded too:
     * it would split the request line's target from its HTTP version.
     */
    fun isSafeUrl(url: String): Boolean = url.none { it.code <= 0x20 || it.code == 0x7F }

    /** Ceiling on a single AIA response body. */
    const val MAX_RESPONSE_BYTES = 1 shl 20 // 1 MiB

    /** Ceiling on the wall-clock time spent reading one AIA response. */
    const val MAX_RESPONSE_MILLIS = 10_000L

    /**
     * Reads [input] to EOF, or returns null once it exceeds [limit] bytes or [budgetMs] of
     * wall-clock time. Null rather than a truncated buffer: a cut-off certificate is not parseable
     * anyway, and "too big" or "too slow" is a failed fetch, which the caller already knows how to
     * treat as "no AIA".
     *
     * The byte cap alone would not bound the time. The socket's timeout is per-read, so a server
     * returning one byte just inside it resets the clock on every iteration and holds the handshake
     * thread for as long as it likes — a slow-drip stall in place of the unbounded buffer. Both
     * limits are needed, and the deadline is checked against [nowNanos] (injectable so a test need
     * not actually wait).
     */
    fun readCapped(
      input: InputStream,
      limit: Int = MAX_RESPONSE_BYTES,
      budgetMs: Long = MAX_RESPONSE_MILLIS,
      nowNanos: () -> Long = System::nanoTime,
    ): ByteArray? {
      val deadline = nowNanos() + budgetMs * 1_000_000
      val out = ByteArrayOutputStream()
      val chunk = ByteArray(8 * 1024)
      while (true) {
        val read = input.read(chunk)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) return null
        out.write(chunk, 0, read)
        // Subtraction, not `>`: overflow-safe across nanoTime's arbitrary origin.
        if (nowNanos() - deadline >= 0) return null
      }
    }

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

    /** Status code, `Location` header (if any), and decoded body of a raw HTTP response. */
    data class HttpResponse(val status: Int, val location: String?, val body: ByteArray)

    /**
     * Splits a raw HTTP/1.0 response into its status code, `Location` header, and body, decoding a
     * `Transfer-Encoding: chunked` body if present. Returns null if the bytes are not a
     * recognisable HTTP response.
     */
    fun parseHttpResponse(raw: ByteArray): HttpResponse? {
      val headerEnd = indexOfCrlfCrlf(raw)
      if (headerEnd < 0) return null
      val lines = String(raw, 0, headerEnd, Charsets.ISO_8859_1).split("\r\n")
      // Status line: "HTTP/1.1 200 OK" — the code is the second token.
      val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
      var location: String? = null
      var chunked = false
      for (i in 1 until lines.size) {
        val colon = lines[i].indexOf(':')
        if (colon < 0) continue
        val name = lines[i].substring(0, colon).trim().lowercase()
        val value = lines[i].substring(colon + 1).trim()
        when (name) {
          "location" -> location = value
          "transfer-encoding" -> if (value.lowercase().contains("chunked")) chunked = true
        }
      }
      val body = raw.copyOfRange(headerEnd + 4, raw.size)
      return HttpResponse(status, location, if (chunked) dechunk(body) else body)
    }

    private fun dechunk(body: ByteArray): ByteArray {
      val out = ByteArrayOutputStream()
      var pos = 0
      while (pos < body.size) {
        val eol = indexOfCrlf(body, pos)
        if (eol < 0) break
        // chunk-size line may carry a `;chunk-extension` suffix; the size is hex before the `;`.
        val size =
          String(body, pos, eol - pos, Charsets.ISO_8859_1)
            .substringBefore(';')
            .trim()
            .toIntOrNull(16) ?: break
        if (size == 0) break // last chunk
        val dataStart = eol + 2
        if (dataStart + size > body.size) break
        out.write(body, dataStart, size)
        pos = dataStart + size + 2 // skip the chunk data and its trailing CRLF
      }
      return out.toByteArray()
    }

    private fun indexOfCrlfCrlf(bytes: ByteArray): Int {
      var i = 0
      while (i + 3 < bytes.size) {
        if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF)
          return i
        i++
      }
      return -1
    }

    private fun indexOfCrlf(bytes: ByteArray, from: Int): Int {
      var i = from
      while (i + 1 < bytes.size) {
        if (bytes[i] == CR && bytes[i + 1] == LF) return i
        i++
      }
      return -1
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
  }
}
