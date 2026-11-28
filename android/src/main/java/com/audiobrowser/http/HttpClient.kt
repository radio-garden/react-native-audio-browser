package com.audiobrowser.http

import com.audiobrowser.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber

class HttpClient {
  companion object {
    const val DEFAULT_USER_AGENT = "react-native-audio-browser"
    const val DEFAULT_CONTENT_TYPE = "application/json"
    private const val TIMEOUT_SECONDS = 30L
  }

  /**
   * Debug builds only: Timber is an `api` dependency, so these lines reach the host app's trees
   * (crash-reporter breadcrumbs included), and `BODY` buffers every response whether or not
   * anything logs it. The credential headers below are redacted even in debug, as logcat ends up in
   * bug reports; add any new one to the list.
   */
  private fun loggingInterceptor() =
    HttpLoggingInterceptor { message -> Timber.d(message) }
      .apply {
        level = HttpLoggingInterceptor.Level.BODY
        redactHeader("Authorization")
        redactHeader("Proxy-Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
      }

  private val client =
    OkHttpClient.Builder()
      .apply { if (BuildConfig.DEBUG) addInterceptor(loggingInterceptor()) }
      .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .build()

  data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val contentType: String = DEFAULT_CONTENT_TYPE,
    val userAgent: String = DEFAULT_USER_AGENT,
  )

  /** [headers] joins the lines of a repeated field with ", ", as RFC 9110 §5.3 combines them. */
  data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String>) {
    val isSuccessful: Boolean
      get() = code in 200..299
  }

  /** A non-2xx response is a success; check [HttpResponse.isSuccessful]. */
  suspend fun request(httpRequest: HttpRequest): Result<HttpResponse> =
    withContext(Dispatchers.IO) {
      try {
        val url =
          httpRequest.url.toHttpUrlOrNull()
            ?: return@withContext Result.failure(
              IllegalArgumentException("Invalid URL: ${httpRequest.url}")
            )

        val requestBuilder = Request.Builder().url(url)

        val headers =
          Headers.Builder()
            .apply {
              httpRequest.headers?.forEach { (name, value) -> add(name, value) }
              // A `User-Agent` header in any case wins over `userAgent`.
              if (get("User-Agent") == null) add("User-Agent", httpRequest.userAgent)
            }
            .build()
        requestBuilder.headers(headers)

        when (httpRequest.method.uppercase()) {
          "GET" -> {
            // Request.Builder defaults to GET.
          }
          "POST",
          "PUT",
          "PATCH" -> {
            val body = httpRequest.body ?: ""
            val mediaType = httpRequest.contentType.toMediaType()
            requestBuilder.method(httpRequest.method, body.toRequestBody(mediaType))
          }
          "DELETE" -> {
            val mediaType = httpRequest.contentType.toMediaType()
            requestBuilder.delete(httpRequest.body?.toRequestBody(mediaType))
          }
          else -> {
            return@withContext Result.failure(
              IllegalArgumentException("Unsupported HTTP method: ${httpRequest.method}")
            )
          }
        }

        val request = requestBuilder.build()
        val response = client.newCall(request).execute()

        val responseBody = response.body?.string() ?: ""
        val responseHeaders =
          response.headers.names().associateWith { response.headers.values(it).joinToString(", ") }

        Result.success(
          HttpResponse(code = response.code, body = responseBody, headers = responseHeaders)
        )
      } catch (e: IOException) {
        Timber.e(e, "HTTP request failed")
        Result.failure(e)
      } catch (e: Exception) {
        Timber.e(e, "Unexpected error during HTTP request")
        Result.failure(e)
      }
    }
}
