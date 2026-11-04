package com.audiobrowser.sonos

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The HTTP client for the speakers, which answer on the local network within moments: a speaker
 * that doesn't is gone, and waiting OkHttp's default 10 s per call would hold up every command.
 */
internal val sonosHttpClient: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .build()
}

/** Thrown when a SOAP control call fails (non-2xx HTTP or a SOAP fault). */
internal class SoapException(message: String, val httpCode: Int?, val fault: SoapFault?) :
  Exception(message)

/**
 * Posts UPnP SOAP actions to a control URL, blocking. Returns the response body, or throws
 * [SoapException] with any SOAP fault on a non-2xx response.
 */
internal class SoapClient(private val httpClient: OkHttpClient) {
  fun execute(controlUrl: String, action: SoapAction): String {
    val body = action.body.toRequestBody(XML_MEDIA_TYPE)
    val request =
      Request.Builder()
        .url(controlUrl)
        // UPnP requires the SOAPACTION value be wrapped in double quotes.
        .header("SOAPACTION", "\"${action.soapAction}\"")
        .post(body)
        .build()

    httpClient.newCall(request).execute().use { response ->
      val responseBody = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val fault = SoapResponseParser.fault(responseBody)
        throw SoapException(
          message =
            "SOAP ${action.soapAction} failed: HTTP ${response.code}" +
              (fault?.let { " UPnPError ${it.errorCode} ${it.errorDescription}" } ?: ""),
          httpCode = response.code,
          fault = fault,
        )
      }
      return responseBody
    }
  }

  private companion object {
    val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
  }
}
