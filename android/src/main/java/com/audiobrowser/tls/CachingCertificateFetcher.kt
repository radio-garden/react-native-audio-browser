package com.audiobrowser.tls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import timber.log.Timber

/**
 * Fetches and caches issuer certificates from AIA "CA Issuers" URLs. Used by
 * [AiaChasingTrustManager] to fill in intermediates a server failed to send.
 *
 * Runs on the TLS handshake thread, so it is bounded on every axis and never throws — a failed,
 * oversized or slow fetch resolves to an empty list, and the handshake then fails exactly as it
 * would have without AIA chasing. Every input here is chosen by a server we are already failing to
 * validate: the AIA extension names the host, port and path, and then that host chooses the
 * response. So:
 * - the URL is rejected unless it is free of control characters ([isSafeUrl]),
 * - a response is capped at [MAX_RESPONSE_BYTES] and [MAX_RESPONSE_MILLIS],
 * - at most [MAX_REDIRECTS] redirects are followed,
 * - the cache holds at most [MAX_CACHE_ENTRIES], and only successful fetches, so a round trip
 *   happens at most once per intermediate while transient failures stay retryable.
 *
 * Both schemes go over a raw [Socket] rather than `HttpURLConnection`. For `http` that is because
 * CA "CA Issuers" URLs are virtually always plain `http` — the CA/Browser Forum Baseline
 * Requirements mandate it, to avoid a chicken-and-egg TLS dependency when fetching the certificate
 * needed to complete a TLS chain — while Android blocks cleartext `HttpURLConnection` traffic by
 * default on apps targeting API 28+. A raw socket is not subject to `NetworkSecurityPolicy`, so the
 * fetch succeeds regardless of the host app's cleartext setting; safely, because the fetched
 * certificate is still cryptographically verified to be the real issuer before
 * [AiaChasingTrustManager] trusts it.
 *
 * For `https` it is because `HttpURLConnection.getInputStream()` performs the connect, request and
 * response *headers* before returning a stream, so a read cap applied to that stream bounds only
 * the body: a server dripping header bytes just inside the per-read timeout would hold the
 * handshake thread for as long as it liked. Reading the headers ourselves puts them inside the same
 * budget as the body. It also keeps one redirect policy — the platform stack would otherwise follow
 * its own, ignoring [MAX_REDIRECTS].
 */
class CachingCertificateFetcher(
  private val connectTimeoutMs: Int = 5_000,
  private val readTimeoutMs: Int = 5_000,
) {
  /**
   * Bounded, least-recently-used. An attacker can name any URL and serve any parseable certificate
   * from it, so an unbounded map would retain a megabyte per hostile handshake for the process
   * lifetime. A real app needs a handful of intermediates.
   */
  private val cache =
    object : LinkedHashMap<String, List<X509Certificate>>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: Map.Entry<String, List<X509Certificate>>) =
        size > MAX_CACHE_ENTRIES
    }

  /**
   * A plain system-default socket factory for `https` AIA fetches. Without it the fetch would use
   * the process default — which, once this library's AIA factory is installed there, would recurse
   * into AIA chasing during the fetch's own handshake.
   */
  private val plainSslSocketFactory: SSLSocketFactory by lazy {
    SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
  }

  fun fetch(url: String): List<X509Certificate> {
    synchronized(cache) { cache[url] }
      ?.let {
        return it
      }
    val certs = download(url)?.let { parseCertificates(it) }.orEmpty()
    if (certs.isNotEmpty()) synchronized(cache) { cache[url] = certs }
    return certs
  }

  private fun download(url: String, redirectsLeft: Int = MAX_REDIRECTS): ByteArray? =
    try {
      require(isSafeUrl(url)) { "AIA URL contains characters that are not valid in a URL" }
      val parsed = URL(url)
      when (parsed.protocol) {
        // restrict to http/https — file:, ftp: etc. are never valid CA-issuer sources
        "http" -> fetchOverSocket(parsed, redirectsLeft, secure = false)
        "https" -> fetchOverSocket(parsed, redirectsLeft, secure = true)
        else -> null
      }
    } catch (e: Exception) {
      // The URL is attacker-supplied text and this is the one place it survives the safety
      // check, so it is escaped rather than handed to the log verbatim.
      Timber.w(e, "AIA CA-issuer fetch failed: %s", sanitizeForLog(url))
      null
    }

  /** One HTTP/1.0 exchange over a socket, TLS-wrapped when [secure], then redirects. */
  private fun fetchOverSocket(url: URL, redirectsLeft: Int, secure: Boolean): ByteArray? {
    val host = url.host
    val port = if (url.port != -1) url.port else if (secure) 443 else 80
    val path = url.file.ifEmpty { "/" } // URL.file is path + query
    val raw =
      connect(host, port, secure).use { socket ->
        socket.soTimeout = readTimeoutMs
        // HTTP/1.0 + `Connection: close` so the server closes after the body and we can read to
        // EOF without parsing Content-Length. A non-default port belongs in Host, or a
        // name-based virtual host answers for the wrong site.
        val hostHeader = if (port == (if (secure) 443 else 80)) host else "$host:$port"
        val request =
          "GET $path HTTP/1.0\r\n" +
            "Host: $hostHeader\r\n" +
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
        // Follow CA-issuer redirects (rare). `download` is the recursion point, so the target
        // goes through the same URL check and the same budget.
        download(URL(url, response.location).toString(), redirectsLeft - 1)
      else -> null
    }
  }

  /**
   * Connects to [host]:[port], wrapping in TLS when [secure].
   *
   * The TLS socket verifies the hostname explicitly. A raw `SSLSocket` does not do it on its own,
   * and `SSLParameters.setEndpointIdentificationAlgorithm` is API 24+, so the platform's default
   * verifier is applied against the negotiated session instead — which works on every supported
   * level and is the same check `HttpsURLConnection` would have made.
   */
  private fun connect(host: String, port: Int, secure: Boolean): Socket {
    val plain = Socket()
    plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
    if (!secure) return plain
    return try {
      (plainSslSocketFactory.createSocket(plain, host, port, true) as SSLSocket).apply {
        soTimeout = readTimeoutMs
        startHandshake()
        require(HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
          "AIA host $host does not match its certificate"
        }
      }
    } catch (e: Exception) {
      plain.close()
      throw e
    }
  }

  companion object {
    private const val MAX_REDIRECTS = 5

    /** Ceiling on how many fetched issuer certificates are retained. */
    const val MAX_CACHE_ENTRIES = 32

    /** Ceiling on a single AIA response, headers included. */
    const val MAX_RESPONSE_BYTES = 1 shl 20 // 1 MiB

    /** Ceiling on the wall-clock time spent reading one AIA response. */
    const val MAX_RESPONSE_MILLIS = 10_000L

    /**
     * Whether [url] is free of characters that have no business in a URL.
     *
     * The fetch writes the path into a request line on a raw socket, and the URL is an IA5String
     * copied verbatim out of a certificate a hostile server presented. `java.net.URL` preserves
     * control characters — `URL("http://host:6379/\r\nSET foo bar")` parses to that host and port
     * with the CRLF intact in `file` — so without this check a certificate could inject request
     * lines of its own, to any host and port the device can reach. Space is excluded too: it would
     * split the request line's target from its HTTP version.
     */
    fun isSafeUrl(url: String): Boolean = url.none { it.code <= 0x20 || it.code == 0x7F }

    /** [text] with anything non-printable replaced, so a log line cannot be forged. */
    fun sanitizeForLog(text: String): String =
      text.map { if (it.code <= 0x20 || it.code == 0x7F) '?' else it }.joinToString("")

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
        // `<= 0`, not `== 0`: toIntOrNull accepts a leading `-`, and a negative size would move
        // `pos` backwards — the same rewinding-cursor loop the DER reader had.
        if (size <= 0) break // last chunk, or malformed
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
