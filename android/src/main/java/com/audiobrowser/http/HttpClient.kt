package com.audiobrowser.http

import com.audiobrowser.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Debug builds only: Timber is an `api` dependency, so these lines reach the host app's trees,
   * and `BODY` buffers every response. Redact every credential header, since logcat ends up in bug
   * reports.
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

  data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
    /** The reason phrase, which OkHttp leaves empty over HTTP/2. */
    val message: String,
  ) {
    val isSuccessful: Boolean
      get() = code in 200..299
  }

  /**
   * Fails only on an invalid URL, an unsupported method or a transport error: a non-2xx response is
   * a success carrying its [HttpResponse.code].
   */
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
              add("User-Agent", httpRequest.userAgent)
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
        val responseHeaders = response.headers.toMap()

        Result.success(
          HttpResponse(
            code = response.code,
            body = responseBody,
            headers = responseHeaders,
            message = response.message,
          )
        )
      } catch (e: IOException) {
        Timber.e(e, "HTTP request failed")
        Result.failure(e)
      } catch (e: Exception) {
        Timber.e(e, "Unexpected error during HTTP request")
        Result.failure(e)
      }
    }

  /** Fails with [HttpException] on a non-2xx response. */
  suspend inline fun <reified T> requestJson(httpRequest: HttpRequest): Result<T> {
    return request(httpRequest).mapCatching { response ->
      if (!response.isSuccessful) {
        throw HttpException(response.code, response.body, response.message)
      }
      json.decodeFromString<T>(response.body)
    }
  }

  /** Fails with [HttpException] on a non-2xx response. */
  suspend fun requestJsonElement(httpRequest: HttpRequest): Result<JsonElement> {
    return request(httpRequest).mapCatching { response ->
      if (!response.isSuccessful) {
        throw HttpException(response.code, response.body, response.message)
      }
      json.parseToJsonElement(response.body)
    }
  }

  /** Its message is the response body's JSON `error` field when it has one, else [reasonPhrase]. */
  class HttpException(val code: Int, val responseBody: String, val reasonPhrase: String) :
    Exception(formatMessage(code, responseBody, reasonPhrase)) {
    companion object {
      private fun formatMessage(code: Int, responseBody: String, reasonPhrase: String): String {
        try {
          val json = org.json.JSONObject(responseBody)
          val errorMessage = json.optString("error", null)
          if (!errorMessage.isNullOrBlank()) {
            return errorMessage
          }
        } catch (_: Exception) {
          // Not JSON: the reason phrase stands.
        }
        return reasonPhrase
      }
    }
  }
}
