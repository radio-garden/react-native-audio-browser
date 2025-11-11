package com.audiobrowser.http

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

  private val loggingInterceptor =
    HttpLoggingInterceptor { message -> Timber.d(message) }
      .apply { level = HttpLoggingInterceptor.Level.BODY }

  private val client =
    OkHttpClient.Builder()
      .addInterceptor(loggingInterceptor)
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

  data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String>) {
    val isSuccessful: Boolean
      get() = code in 200..299
  }

  suspend fun request(httpRequest: HttpRequest): Result<HttpResponse> =
    withContext(Dispatchers.IO) {
      try {
        val url =
          httpRequest.url.toHttpUrlOrNull()
            ?: return@withContext Result.failure(
              IllegalArgumentException("Invalid URL: ${httpRequest.url}")
            )

        val requestBuilder = Request.Builder().url(url)

        // Add headers
        val headers =
          Headers.Builder()
            .apply {
              httpRequest.headers?.forEach { (name, value) -> add(name, value) }
              // Set User-Agent (userAgent parameter takes precedence over headers)
              add("User-Agent", httpRequest.userAgent)
            }
            .build()
        requestBuilder.headers(headers)

        // Add body for non-GET requests
        when (httpRequest.method.uppercase()) {
          "GET" -> {
            /* No body for GET */
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

  suspend inline fun <reified T> requestJson(httpRequest: HttpRequest): Result<T> {
    return request(httpRequest).mapCatching { response ->
      if (!response.isSuccessful) {
        throw HttpException(response.code, response.body)
      }
      json.decodeFromString<T>(response.body)
    }
  }

  suspend fun requestJsonElement(httpRequest: HttpRequest): Result<JsonElement> {
    return request(httpRequest).mapCatching { response ->
      if (!response.isSuccessful) {
        throw HttpException(response.code, response.body)
      }
      json.parseToJsonElement(response.body)
    }
  }

  class HttpException(val code: Int, val responseBody: String) :
    Exception("HTTP $code: $responseBody")
}
