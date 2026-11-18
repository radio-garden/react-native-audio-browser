package com.audiobrowser.sonos

internal object SonosStreamUri {
  /**
   * A [live] http(s) stream goes as `x-rincon-mp3radio://` and the whole URL; despite the name, the
   * speaker sniffs any codec (MP3, AAC, HLS) and follows redirects. A track goes as its plain URL:
   * under the radio scheme the speaker reports no duration and refuses `Seek`.
   */
  fun forTransport(url: String, live: Boolean): String {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    return if (live && (scheme == "http" || scheme == "https")) "x-rincon-mp3radio://$url" else url
  }
}
