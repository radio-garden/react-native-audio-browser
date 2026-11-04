package com.audiobrowser.sonos

/**
 * The `CurrentURI` a Sonos speaker plays an http(s) stream from: `x-rincon-mp3radio://` followed by
 * the whole URL, scheme included. The speaker sniffs the codec itself (MP3, AAC, HLS) and follows
 * redirects. Any other scheme passes through unchanged.
 */
internal object SonosStreamUri {
  fun forTransport(url: String): String {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    return if (scheme == "http" || scheme == "https") "x-rincon-mp3radio://$url" else url
  }
}
