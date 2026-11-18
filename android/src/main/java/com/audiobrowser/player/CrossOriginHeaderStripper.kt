package com.audiobrowser.player

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Drops the caller's headers from a redirect hop that leaves the origin the call addressed.
 *
 * OkHttp's redirect follow-up removes only `Authorization`; every other configured header (an
 * `X-Api-Key`, a cookie) would reach whichever host the redirect names. iOS and web forward none.
 *
 * Must be installed as a network interceptor: an application interceptor sees only the first
 * request, not each hop. `chain.call().request()` is the call's original request, so each hop is
 * compared against the URL the caller addressed, not the hop before it.
 *
 * Covers redirects only. An HLS segment or key fetch is its own call to the CDN URL, and
 * [TransformingDataSource] applies the config's headers to it on purpose.
 */
internal object CrossOriginHeaderStripper : Interceptor {

  /**
   * Lowercase names of headers OkHttp and media3 set per hop, which the request breaks without:
   * `range` carries the seek, so dropping it restarts the read from zero.
   *
   * `icy-metadata` is media3's request for Shoutcast metadata, arriving on the DataSpec alongside
   * the config's headers. Dropping it loses `icy-metaint`: "now playing" stops on redirected
   * streams and the interleaved metadata bytes play as audible chirps.
   */
  private val TRANSPORT_HEADERS =
    setOf(
      "accept",
      "accept-encoding",
      "connection",
      "content-length",
      "host",
      "icy-metadata",
      "range",
      "transfer-encoding",
      "user-agent",
    )

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val addressed = chain.call().request().url
    return chain.proceed(
      if (sameOrigin(addressed, request.url)) request else stripCallerHeaders(request)
    )
  }

  /** Scheme, host and port: the same test web's `followMediaRedirect` applies. */
  internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
    a.scheme == b.scheme && a.host == b.host && a.port == b.port

  internal fun stripCallerHeaders(request: Request): Request =
    request
      .newBuilder()
      .apply {
        request.headers.names().forEach { name ->
          if (name.lowercase() !in TRANSPORT_HEADERS) removeHeader(name)
        }
      }
      .build()
}
