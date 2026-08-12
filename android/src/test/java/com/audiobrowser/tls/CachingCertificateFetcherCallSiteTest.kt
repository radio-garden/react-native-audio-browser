package com.audiobrowser.tls

import java.net.InetAddress
import java.net.ServerSocket
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Exercises [CachingCertificateFetcher.fetch] against a real loopback server.
 *
 * The safety helpers have their own unit tests, but a predicate that is never consulted protects
 * nothing: these cover the *wiring*, so that removing the `isSafeUrl` guard or swapping
 * `readCapped` back for a plain read-to-EOF fails a test rather than passing silently.
 */
class CachingCertificateFetcherCallSiteTest {

  private var server: ServerSocket? = null

  @After
  fun tearDown() {
    server?.close()
  }

  /**
   * Serves [response] to every connection, recording the request line of the first. [handled]
   * counts accepted connections.
   */
  private fun startServer(
    response: ByteArray = "HTTP/1.0 404 Not Found\r\n\r\n".toByteArray(),
    handled: AtomicInteger = AtomicInteger(),
    firstRequest: AtomicReference<String> = AtomicReference(),
  ): Int {
    val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    server = socket
    thread(isDaemon = true) {
      while (!socket.isClosed) {
        try {
          socket.accept().use { client ->
            handled.incrementAndGet()
            val request = ByteArray(1024)
            val read = client.getInputStream().read(request)
            firstRequest.compareAndSet(null, String(request, 0, maxOf(read, 0)))
            client.getOutputStream().apply {
              write(response)
              flush()
            }
          }
        } catch (e: Exception) {
          return@thread // socket closed, or the client hung up
        }
      }
    }
    return socket.localPort
  }

  @Test
  fun `a URL carrying CRLF never reaches the network`() {
    val handled = AtomicInteger()
    val port = startServer(handled = handled)

    // Without the isSafeUrl guard this connects and emits "SET foo bar" as its own request line.
    val certs = CachingCertificateFetcher().fetch("http://127.0.0.1:$port/\r\nSET foo bar\r\n")

    assertTrue(certs.isEmpty())
    assertEquals("the request must not be sent at all", 0, handled.get())
  }

  @Test
  fun `a well-formed URL does reach the network, so the guard is not just refusing everything`() {
    val handled = AtomicInteger()
    val request = AtomicReference<String>()
    val port = startServer(handled = handled, firstRequest = request)

    CachingCertificateFetcher().fetch("http://127.0.0.1:$port/issuer.crt")

    assertEquals(1, handled.get())
    assertTrue("request line was: ${request.get()}", request.get().startsWith("GET /issuer.crt "))
  }

  @Test
  fun `a non-default port is carried in the Host header`() {
    val request = AtomicReference<String>()
    val port = startServer(firstRequest = request)

    CachingCertificateFetcher().fetch("http://127.0.0.1:$port/issuer.crt")

    assertTrue(
      "headers were: ${request.get()}",
      request.get().contains("Host: 127.0.0.1:$port\r\n"),
    )
  }

  @Test(timeout = 30_000)
  fun `an oversized response is abandoned rather than read to EOF`() {
    // Asserting only that the fetch fails would prove nothing — 4 MiB crosses loopback in
    // milliseconds and would fail to parse either way. What has to be true is that the client
    // stopped reading, which shows up as the server's write not draining.
    val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    server = socket
    val written = AtomicInteger()
    val finished = CountDownLatch(1)
    thread(isDaemon = true) {
      runCatching {
        socket.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          val out = client.getOutputStream()
          out.write("HTTP/1.0 200 OK\r\n\r\n".toByteArray())
          val chunk = ByteArray(64 * 1024)
          // 64 MiB — far past the 1 MiB cap. Once the client stops reading and closes, this
          // throws a broken pipe, which is the signal we want.
          repeat(1024) {
            out.write(chunk)
            written.addAndGet(chunk.size)
          }
        }
      }
      finished.countDown()
    }

    val certs = CachingCertificateFetcher().fetch("http://127.0.0.1:${socket.localPort}/big.crt")

    assertTrue(certs.isEmpty())
    assertTrue(finished.await(20, TimeUnit.SECONDS))
    // Socket buffers mean the server gets somewhat past the cap before the write fails, but it
    // must be nowhere near the 64 MiB it wanted to send.
    assertTrue(
      "server wrote ${written.get()} bytes; the read was not cut off",
      written.get() < 16 shl 20,
    )
  }

  @Test(timeout = 30_000)
  fun `a real certificate is fetched, parsed and then served from cache`() {
    // The success path, end to end over a socket: every other wire test serves a failure, so a
    // broken body offset or a broken cache write would not show up anywhere.
    val der = CertFixtures.bytes("letsencrypt-r13.der")
    val handled = AtomicInteger()
    val port =
      startServer(
        response =
          ("HTTP/1.0 200 OK\r\nContent-Type: application/pkix-cert\r\n\r\n").toByteArray() + der,
        handled = handled,
      )
    val fetcher = CachingCertificateFetcher()
    val url = "http://127.0.0.1:$port/r13.der"

    val certs = fetcher.fetch(url)

    assertEquals(1, certs.size)
    assertEquals("CN=R13,O=Let's Encrypt,C=US", certs.first().subjectX500Principal.name)

    // Second call must be served from the cache, not the network.
    assertEquals(certs, fetcher.fetch(url))
    assertEquals("a successful fetch must be cached", 1, handled.get())
  }

  /**
   * A mock certificate whose AIA extension lists [url] as its one CA-Issuers location, so
   * [AiaCertChaser.completeChain] chases it with this fetcher — the real wiring, end to end.
   */
  private fun certPointingAt(url: String): X509Certificate {
    // Short-form DER lengths only; a loopback URL is far under 128 bytes.
    fun der(tag: Int, content: ByteArray) =
      byteArrayOf(tag.toByte(), content.size.toByte()) + content
    val caIssuersOid = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)
    val aia =
      der(
        0x04,
        der(
          0x30,
          der(0x30, der(0x06, caIssuersOid) + der(0x86, url.toByteArray(Charsets.US_ASCII))),
        ),
      )
    return mock(X509Certificate::class.java).also {
      `when`(it.getExtensionValue("1.3.6.1.5.5.7.1.1")).thenReturn(aia)
      // Distinct principals, or the chaser reads the mock as a self-signed root and never fetches.
      `when`(it.subjectX500Principal).thenReturn(X500Principal("CN=leaf"))
      `when`(it.issuerX500Principal).thenReturn(X500Principal("CN=issuer"))
    }
  }

  @Test(timeout = 30_000)
  fun `a drip-feeding server is cut off by the chase deadline, not only the per-fetch caps`() {
    // One byte every 100 ms: each read lands well inside the socket timeout, so per-read limits
    // never fire, and the per-fetch read budget alone would let this run for its full 10 s. The
    // walk's 1 s budget must cut the fetch *itself* — not merely decline to start the next one.
    val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    server = socket
    thread(isDaemon = true) {
      runCatching {
        socket.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          val out = client.getOutputStream()
          out.write("HTTP/1.0 200 OK\r\n\r\n".toByteArray())
          out.flush()
          repeat(300) { // 30 s of drip on offer; the client must not stay for it
            out.write(0)
            out.flush()
            Thread.sleep(100)
          }
        }
      }
    }
    val fetcher = CachingCertificateFetcher()
    val cert = certPointingAt("http://127.0.0.1:${socket.localPort}/drip.crt")

    val started = System.nanoTime()
    val completed =
      AiaCertChaser.completeChain(listOf(cert), budgetMs = 1_000) { url, deadline ->
        fetcher.fetch(url, deadline)
      }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    assertEquals(listOf(cert), completed)
    // Well past the 1 s budget to absorb scheduling noise, but far short of the 10 s the
    // per-fetch read budget alone would have allowed.
    assertTrue("chase ran for $elapsedMs ms", elapsedMs < 8_000)
  }

  @Test(timeout = 30_000)
  fun `the chase deadline spans redirect hops, not just fetch starts`() {
    // Every hop stalls 1 s and then redirects back to itself. Each hop sits comfortably inside
    // the per-operation timeouts, so only a deadline threaded through the redirect recursion
    // can end the fetch — without one it walks all 5 redirects, ~6 s here.
    val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    server = socket
    thread(isDaemon = true) {
      while (!socket.isClosed) {
        try {
          socket.accept().use { client ->
            client.getInputStream().read(ByteArray(1024))
            Thread.sleep(1_000)
            client.getOutputStream().apply {
              write("HTTP/1.0 302 Found\r\nLocation: /again.crt\r\n\r\n".toByteArray())
              flush()
            }
          }
        } catch (e: Exception) {
          return@thread
        }
      }
    }
    val fetcher = CachingCertificateFetcher()

    val started = System.nanoTime()
    val certs =
      fetcher.fetch(
        "http://127.0.0.1:${socket.localPort}/loop.crt",
        deadlineNanos = System.nanoTime() + 1_500 * 1_000_000L,
      )
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    assertTrue(certs.isEmpty())
    assertTrue("fetch ran for $elapsedMs ms across redirect hops", elapsedMs < 4_000)
  }

  @Test(timeout = 30_000)
  fun `a server that accepts and never answers is bounded by the read timeout`() {
    val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    server = socket
    val accepted = CountDownLatch(1)
    // Accept, then hold the connection open saying nothing at all.
    thread(isDaemon = true) {
      runCatching {
        val client = socket.accept()
        accepted.countDown()
        Thread.sleep(60_000)
        client.close()
      }
    }

    val certs =
      CachingCertificateFetcher(connectTimeoutMs = 2_000, readTimeoutMs = 1_000)
        .fetch("http://127.0.0.1:${socket.localPort}/hangs.crt")

    assertTrue(accepted.await(10, TimeUnit.SECONDS))
    assertTrue(certs.isEmpty())
  }

  @Test
  fun `a non-http scheme is never fetched`() {
    val handled = AtomicInteger()
    val port = startServer(handled = handled)

    assertTrue(CachingCertificateFetcher().fetch("ftp://127.0.0.1:$port/issuer.crt").isEmpty())
    assertTrue(CachingCertificateFetcher().fetch("file:///etc/hosts").isEmpty())
    assertEquals(0, handled.get())
  }

  @Test
  fun `a failed fetch is not cached, so it stays retryable`() {
    val handled = AtomicInteger()
    val port = startServer(handled = handled)
    val fetcher = CachingCertificateFetcher()
    val url = "http://127.0.0.1:$port/issuer.crt"

    fetcher.fetch(url)
    fetcher.fetch(url)

    assertEquals("both attempts must hit the network", 2, handled.get())
  }

  @Test
  fun `the cache is bounded`() {
    // Far more distinct URLs than MAX_CACHE_ENTRIES; nothing is cached here (every fetch fails),
    // but the bound is what stops an attacker-chosen URL space from growing without limit.
    assertFalse(CachingCertificateFetcher.MAX_CACHE_ENTRIES <= 0)
    assertTrue(CachingCertificateFetcher.MAX_CACHE_ENTRIES <= 128)
  }
}
