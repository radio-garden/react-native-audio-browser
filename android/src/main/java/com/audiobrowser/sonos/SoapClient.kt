package com.audiobrowser.sonos

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Short timeouts: a speaker that doesn't answer within seconds is gone, and queued commands wait.
internal val sonosHttpClient: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .build()
}

internal class SoapException(message: String, val httpCode: Int?, val fault: SoapFault?) :
  Exception(message)

internal class SoapClient(private val httpClient: OkHttpClient) {
  /**
   * Posts [action] to [controlUrl], blocking, and returns the response body. Throws [SoapException]
   * on a non-2xx response, and OkHttp's `IOException` when the speaker doesn't answer.
   */
  fun execute(controlUrl: String, action: SoapAction): String {
    val body = action.body.toRequestBody(XML_MEDIA_TYPE)
    val request =
      Request.Builder()
        .url(controlUrl)
        // UPnP requires the value in double quotes.
        .header("SOAPACTION", "\"${action.soapAction}\"")
        .post(body)
        .build()

    httpClient.newCall(request).execute().use { response ->
      val responseBody = response.speakerBody()
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
