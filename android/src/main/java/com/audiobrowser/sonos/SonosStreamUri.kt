package com.audiobrowser.sonos

/**
 * The `CurrentURI` a Sonos speaker plays a URL from. A live http(s) stream goes as
 * `x-rincon-mp3radio://` followed by the whole URL, scheme included: the speaker sniffs the codec
 * itself (MP3, AAC, HLS) and follows redirects. A track goes as its URL, since under the radio
 * scheme the speaker reports no duration and refuses `Seek`. Any other scheme passes through
 * unchanged.
 */
internal object SonosStreamUri {
  fun forTransport(url: String, live: Boolean): String {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    return if (live && (scheme == "http" || scheme == "https")) "x-rincon-mp3radio://$url" else url
  }
}
