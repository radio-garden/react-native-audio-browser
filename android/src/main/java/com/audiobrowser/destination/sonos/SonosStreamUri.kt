package com.audiobrowser.destination.sonos

/**
 * Normalizes a stream URL to the scheme Sonos plays reliably.
 *
 * Sonos starts a raw continuous MP3/ICY radio stream dependably only when the http(s) URL is handed
 * to `SetAVTransportURI` under the **`x-rincon-mp3radio://`** scheme (the long-standing approach in
 * the Sonos community: node-sonos, SoCo). Segmented or known-container formats — HLS (`.m3u8`), DASH
 * (`.mpd`), and discrete codecs (`.aac`, `.flac`, `.ogg`, …) — play over plain http(s) and must NOT
 * be rewritten. URLs already on a non-http scheme (`x-rincon-mp3radio://`, `x-sonosapi-stream:`) are
 * passed through untouched.
 *
 * This is a heuristic and a documented hardware-verify item (see the Sonos guide): RG's live streams
 * are predominantly extensionless MP3/ICY mounts, the case the rewrite targets.
 */
object SonosStreamUri {
  // Formats Sonos fetches itself over plain http(s); never rewritten.
  private val CONTAINER_EXTENSIONS =
    setOf("m3u8", "m3u", "mpd", "aac", "flac", "ogg", "oga", "opus", "wav", "mp4", "m4a")
  private val CONTAINER_CONTENT_TYPES =
    setOf(
      "application/vnd.apple.mpegurl",
      "application/x-mpegurl",
      "application/dash+xml",
      "audio/aac",
      "audio/flac",
      "audio/ogg",
      "audio/wav",
      "audio/mp4",
      "audio/opus",
    )

  fun forTransport(url: String, contentType: String? = null): String {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    // Only rewrite plain http(s); anything else is already a special/relative form — leave it.
    if (scheme != "http" && scheme != "https") return url

    val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (ct != null && ct in CONTAINER_CONTENT_TYPES) return url

    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val extension = path.substringAfterLast('/', "").substringAfterLast('.', "")
    if (extension in CONTAINER_EXTENSIONS) return url

    // Treat everything else (mp3, extensionless ICY mounts, audio/mpeg) as MP3 radio.
    return "x-rincon-mp3radio://" + url.substringAfter("://")
  }
}
