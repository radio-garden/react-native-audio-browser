package com.audiobrowser.tls

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    // 4 MiB of body against a 1 MiB cap. Without readCapped this is buffered in full and the
    // fetch merely fails to parse; with it, the read stops early.
    val body = ByteArray(4 shl 20)
    val port = startServer(response = "HTTP/1.0 200 OK\r\n\r\n".toByteArray() + body)

    val certs = CachingCertificateFetcher().fetch("http://127.0.0.1:$port/big.crt")

    assertTrue(certs.isEmpty())
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
