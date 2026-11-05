package com.audiobrowser.tls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * Fetches and caches issuer certificates from AIA "CA Issuers" URLs, for [AiaChasingTrustManager]
 * to fill in intermediates a server failed to send.
 *
 * Runs on the TLS handshake thread and never throws: a failed, oversized or slow fetch is an empty
 * list. Every input is chosen by the server being validated (the AIA URL names host, port and path;
 * that host picks the response), so the URL must pass [isSafeUrl], a response is capped at
 * [MAX_RESPONSE_BYTES] and [MAX_RESPONSE_MILLIS], at most [MAX_REDIRECTS] redirects are followed,
 * and every connect, handshake, read and redirect hop ends at the caller's deadline. Only
 * successful fetches are cached, so failures stay retryable.
 *
 * DNS is the one step no deadline reaches: `InetSocketAddress` resolves synchronously with no
 * timeout, so a lookup started just inside the deadline can overrun it. None is started past it,
 * and [AiaCertChaser.MAX_URLS_PER_CERT] bounds how many names a certificate can make us resolve.
 *
 * Both schemes go over a raw [Socket], not `HttpURLConnection`:
 * - `http`: CA Issuers URLs are plain `http` (the CA/Browser Forum Baseline Requirements mandate
 *   it), and Android blocks cleartext `HttpURLConnection` from API 28 targets. A raw socket is not
 *   subject to `NetworkSecurityPolicy`; the fetched certificate is still verified as the real
 *   issuer before it is trusted.
 * - `https`: `HttpURLConnection` reads the response headers before handing back a stream, outside
 *   any read cap, so a server dripping header bytes could hold the handshake thread indefinitely.
 *   It would also follow its own redirect policy instead of [MAX_REDIRECTS].
 */
class CachingCertificateFetcher(
  private val connectTimeoutMs: Int = 5_000,
  private val readTimeoutMs: Int = 5_000,
  private val nowNanos: () -> Long = System::nanoTime,
) {
  // LRU and bounded: a hostile server can name any URL and serve any parseable certificate, so an
  // unbounded map would grow per hostile handshake for the life of the process.
  private val cache =
    object : LinkedHashMap<String, List<X509Certificate>>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: Map.Entry<String, List<X509Certificate>>) =
        size > MAX_CACHE_ENTRIES
    }

  // System-default, not the process default: once this library's AIA factory is installed as the
  // process default, an `https` fetch through it would recurse into AIA chasing.
  private val plainSslSocketFactory: SSLSocketFactory by lazy {
    SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
  }

  /**
   * The certificates served at [url], or empty on any failure. Gives up by [deadlineNanos],
   * absolute on [nowNanos]'s clock; [AiaCertChaser.completeChain] passes its whole-chase deadline
   * so one budget bounds every fetch it makes. A cache hit is answered even past the deadline.
   */
  fun fetch(
    url: String,
    deadlineNanos: Long = nowNanos() + AiaCertChaser.MAX_CHASE_MILLIS * 1_000_000,
  ): List<X509Certificate> {
    synchronized(cache) { cache[url] }
      ?.let {
        return it
      }
    val certs = download(url, deadlineNanos)?.let { parseCertificates(it) }.orEmpty()
    if (certs.isNotEmpty()) synchronized(cache) { cache[url] = certs }
    return certs
  }

  private fun remainingMs(deadlineNanos: Long): Long = (deadlineNanos - nowNanos()) / 1_000_000

  private fun download(
    url: String,
    deadlineNanos: Long,
    redirectsLeft: Int = MAX_REDIRECTS,
  ): ByteArray? =
    try {
      // Before anything starts, DNS included. Redirects recurse through here, so every hop
      // re-checks the deadline and the URL.
      require(remainingMs(deadlineNanos) > 0) { "AIA chase budget exhausted" }
      require(isSafeUrl(url)) { "AIA URL contains characters that are not valid in a URL" }
      val parsed = URL(url)
      when (parsed.protocol) {
        "http" -> fetchOverSocket(parsed, deadlineNanos, redirectsLeft, secure = false)
        "https" -> fetchOverSocket(parsed, deadlineNanos, redirectsLeft, secure = true)
        else -> null
      }
    } catch (e: Exception) {
      // The URL is server-chosen and may be the one that failed isSafeUrl.
      Timber.w(e, "AIA CA-issuer fetch failed: %s", sanitizeForLog(url))
      null
    }

  /**
   * The socket to speak HTTP over, and the plain TCP [transport] beneath it (the same socket for
   * `http`). Closing an `SSLSocket` writes `close_notify`, which can block against a peer that
   * stopped reading, so whatever must unblock a stalled call closes [transport].
   */
  private class Connection(val socket: Socket, val transport: Socket)

  private fun fetchOverSocket(
    url: URL,
    deadlineNanos: Long,
    redirectsLeft: Int,
    secure: Boolean,
  ): ByteArray? {
    val host = url.host
    val port = if (url.port != -1) url.port else if (secure) 443 else 80
    val path = url.file.ifEmpty { "/" } // path + query
    val connection = connect(host, port, secure, deadlineNanos)
    val raw =
      try {
        withWatchdog(
          minOf(remainingMs(deadlineNanos), connectTimeoutMs + MAX_RESPONSE_MILLIS),
          abort = { connection.transport.close() },
        ) {
          val socket = connection.socket
          socket.soTimeout = timeoutMs(deadlineNanos, readTimeoutMs)
          // HTTP/1.0 with `Connection: close`: the server closes after the body, so it is read to
          // EOF without Content-Length. A non-default port belongs in Host, or a name-based
          // virtual host answers for the wrong site.
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
          socket.getInputStream().use {
            readCapped(
              it,
              budgetMs = minOf(remainingMs(deadlineNanos), MAX_RESPONSE_MILLIS),
              nowNanos = nowNanos,
            )
          }
        }
      } finally {
        // Transport first, so the SSLSocket's close_notify then fails fast instead of blocking.
        runCatching { connection.transport.close() }
        runCatching { connection.socket.close() }
      } ?: return null
    val response = parseHttpResponse(raw) ?: return null
    return when {
      response.status in 200..299 -> response.body
      response.status in 300..399 && response.location != null && redirectsLeft > 0 ->
        download(URL(url, response.location).toString(), deadlineNanos, redirectsLeft - 1)
      else -> null
    }
  }

  /**
   * Connects to [host]:[port], wrapping in TLS when [secure], with the connect and handshake
   * clipped to [deadlineNanos]. The hostname is verified with the platform's default verifier: a
   * raw `SSLSocket` does not verify it, and `setEndpointIdentificationAlgorithm` is API 24+.
   */
  private fun connect(host: String, port: Int, secure: Boolean, deadlineNanos: Long): Connection {
    val plain = Socket()
    // Android's Socket.connect, unlike OpenJDK's, does not close itself on failure, so the catch
    // below closes `plain` or every unreachable AIA host leaks a descriptor until finalization.
    var wrapper: SSLSocket? = null
    return try {
      plain.connect(InetSocketAddress(host, port), timeoutMs(deadlineNanos, connectTimeoutMs))
      if (!secure) return Connection(plain, plain)
      // soTimeout is per-read, so a peer drip-feeding handshake bytes would outlast it; the
      // watchdog closes the transport, not the SSLSocket (see Connection).
      withWatchdog(
        minOf(remainingMs(deadlineNanos), connectTimeoutMs + MAX_RESPONSE_MILLIS),
        abort = { plain.close() },
      ) {
        val ssl = plainSslSocketFactory.createSocket(plain, host, port, true) as SSLSocket
        wrapper = ssl
        ssl.soTimeout = timeoutMs(deadlineNanos, readTimeoutMs)
        ssl.startHandshake()
        require(HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
          "AIA host $host does not match its certificate"
        }
        Connection(ssl, plain)
      }
    } catch (e: Exception) {
      runCatching { plain.close() }
      // Frees the wrapper's buffers now instead of at GC; with the transport closed, its
      // close_notify fails fast.
      runCatching { wrapper?.close() }
      throw e
    }
  }

  // Never 0, which a socket reads as "no timeout": a passed deadline gets 1 ms and fails at once.
  private fun timeoutMs(deadlineNanos: Long, perOperationMs: Int): Int =
    minOf(remainingMs(deadlineNanos), perOperationMs.toLong())
      .coerceIn(1, Int.MAX_VALUE.toLong())
      .toInt()

  /**
   * Runs [body], calling [abort] from another thread if it is still running after [budgetMs].
   * Socket timeouts are per-operation, so this is what ends a peer that keeps every read just
   * inside them: [abort] closes the transport and the blocked call throws.
   */
  private inline fun <T> withWatchdog(
    budgetMs: Long,
    crossinline abort: () -> Unit,
    body: () -> T,
  ): T {
    val done = AtomicBoolean(false)
    val watchdog =
      thread(isDaemon = true, name = "aia-fetch-watchdog") {
        runCatching { Thread.sleep(budgetMs.coerceAtLeast(0)) }
          .onSuccess { if (!done.get()) runCatching { abort() } }
      }
    try {
      return body()
    } finally {
      done.set(true)
      watchdog.interrupt()
    }
  }

  companion object {
    private const val MAX_REDIRECTS = 5

    const val MAX_CACHE_ENTRIES = 32

    /** Ceiling on one AIA response, headers included. */
    const val MAX_RESPONSE_BYTES = 1 shl 20

    /** Ceiling on the wall-clock time spent reading one AIA response. */
    const val MAX_RESPONSE_MILLIS = 10_000L

    /**
     * Whether [url] is free of control characters, space and DEL. The path is written into a
     * request line on a raw socket, and `java.net.URL` keeps a CRLF in `file`, so a certificate's
     * AIA URL could otherwise inject request lines to any host and port the device can reach; a
     * space would split the request target from the HTTP version.
     */
    fun isSafeUrl(url: String): Boolean = url.none { it.code <= 0x20 || it.code == 0x7F }

    /** [text] with what [isSafeUrl] refuses replaced by `?`, so a log line cannot be forged. */
    fun sanitizeForLog(text: String): String =
      text.map { if (it.code <= 0x20 || it.code == 0x7F) '?' else it }.joinToString("")

    /**
     * Every certificate in what a CA Issuers URL serves: a DER certificate, PEM, or a PKCS#7
     * certs-only bundle. Empty if none parse.
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
     * [input] read to EOF, or null once it passes [limit] bytes or [budgetMs] of wall-clock time,
     * as measured by [nowNanos]. The time cap matters as much as the byte cap: a socket timeout is
     * per-read, so a server sending a byte just inside it would otherwise hold the thread for as
     * long as the byte cap lasts.
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

    data class HttpResponse(val status: Int, val location: String?, val body: ByteArray)

    /**
     * Status, `Location` and body of a raw HTTP response, with a chunked body decoded. Null if
     * there is no header terminator or no numeric status.
     */
    fun parseHttpResponse(raw: ByteArray): HttpResponse? {
      val headerEnd = indexOfCrlfCrlf(raw)
      if (headerEnd < 0) return null
      val lines = String(raw, 0, headerEnd, Charsets.ISO_8859_1).split("\r\n")
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
        val size =
          String(body, pos, eol - pos, Charsets.ISO_8859_1)
            .substringBefore(';')
            .trim()
            .toIntOrNull(16) ?: break
        // `<= 0`: 0 is the last chunk, and toIntOrNull accepts a `-` that would move `pos` back.
        if (size <= 0) break
        val dataStart = eol + 2
        if (dataStart + size > body.size) break
        out.write(body, dataStart, size)
        pos = dataStart + size + 2 // past the chunk's trailing CRLF
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
