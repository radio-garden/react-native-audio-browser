package com.audiobrowser.player

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Drops the caller's headers from a redirect hop that leaves the origin the call addressed, as iOS
 * and web do. OkHttp's redirect follow-up removes only `Authorization`.
 *
 * Install it as a network interceptor: an application interceptor sees only the first request, not
 * each hop. Each hop is compared with the call's original URL, not with the hop before it.
 *
 * Covers redirects only: an HLS segment or key fetch is its own call, and [TransformingDataSource]
 * applies the config's headers to it on purpose.
 */
internal object CrossOriginHeaderStripper : Interceptor {

  // Set per hop by OkHttp and media3, and needed by the request: dropping `range` restarts the read
  // from zero, and dropping `icy-metadata` loses `icy-metaint`, so metadata stops and its bytes
  // play as audio.
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

  /** Scheme, host and port: the same test as web's `followMediaRedirect`. */
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
