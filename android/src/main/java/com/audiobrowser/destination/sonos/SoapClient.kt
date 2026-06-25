package com.audiobrowser.destination.sonos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Thrown when a SOAP control call fails (non-2xx HTTP or a SOAP fault). */
class SoapException(
  message: String,
  val httpCode: Int?,
  val fault: SoapFault?,
) : Exception(message)

/**
 * Posts UPnP SOAP control actions to a device's control URL over HTTP. Blocking — callers run it off
 * the main thread (the [SonosPlayer] uses `Dispatchers.IO`). On a non-2xx response it parses any SOAP
 * fault out of the body and throws [SoapException]; on 2xx it returns the response body verbatim for
 * the caller to parse.
 */
class SoapClient(private val httpClient: OkHttpClient) {
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
