package com.audiobrowser.sonos

/** The fields of an SSDP search response that discovery reads. */
internal data class SsdpResponse(
  val location: String,
  val usn: String,
  val searchTarget: String?,
  val server: String?,
)

internal object SsdpMessages {
  const val MULTICAST_HOST = "239.255.255.250"
  const val MULTICAST_PORT = 1900

  /** Sonos players answer M-SEARCH for the ZonePlayer device type. */
  const val SONOS_SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

  private const val USER_AGENT = "AudioBrowser/1.0 UPnP/1.1"

  /** Builds the `M-SEARCH` datagram; `mx` is the seconds a device may wait before replying. */
  fun buildMSearch(searchTarget: String = SONOS_SEARCH_TARGET, mx: Int = 2): ByteArray =
    ("M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $MULTICAST_HOST:$MULTICAST_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $mx\r\n" +
        "ST: $searchTarget\r\n" +
        "USER-AGENT: $USER_AGENT\r\n" +
        "\r\n")
      .toByteArray(Charsets.UTF_8)

  /**
   * Returns null unless [raw] is an HTTP `200` reply with a non-empty `LOCATION` (the device
   * description URL) and `USN` (its identity). NOTIFY announcements are refused. Header names are
   * case-insensitive, the first of a repeated header wins, and bare-LF framing is accepted.
   */
  fun parseResponse(raw: String): SsdpResponse? {
    val lines = raw.replace("\r\n", "\n").split("\n")
    val statusLine = lines.firstOrNull()?.trim() ?: return null
    val statusTokens = statusLine.split(' ')
    if (!statusLine.startsWith("HTTP/") || statusTokens.getOrNull(1) != "200") return null

    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
      if (line.isBlank()) continue
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val key = line.substring(0, colon).trim().lowercase()
      val value = line.substring(colon + 1).trim()
      headers.putIfAbsent(key, value)
    }

    val location = headers["location"]?.takeIf { it.isNotEmpty() } ?: return null
    val usn = headers["usn"]?.takeIf { it.isNotEmpty() } ?: return null
    return SsdpResponse(
      location = location,
      usn = usn,
      searchTarget = headers["st"],
      server = headers["server"],
    )
  }
}
