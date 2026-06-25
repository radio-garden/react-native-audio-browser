package com.audiobrowser.destination.sonos

/** Parsed fields of an SSDP discovery response we care about. */
data class SsdpResponse(
  val location: String,
  val usn: String,
  val searchTarget: String?,
  val server: String?,
)

/**
 * Pure build/parse of the two SSDP messages Sonos discovery needs: the outbound `M-SEARCH`
 * datagram and the inbound search responses. No sockets — [SsdpDiscovery] owns the I/O.
 */
object SsdpMessages {
  const val MULTICAST_HOST = "239.255.255.250"
  const val MULTICAST_PORT = 1900

  /** Sonos players answer M-SEARCH for the ZonePlayer device type. */
  const val SONOS_SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

  /**
   * Builds the canonical SSDP `M-SEARCH` request datagram. Header order and CRLF framing are fixed
   * (some stacks are picky); the body is always terminated by a blank line.
   */
  fun buildMSearch(searchTarget: String = SONOS_SEARCH_TARGET, mx: Int = 1): ByteArray =
    ("M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $MULTICAST_HOST:$MULTICAST_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $mx\r\n" +
        "ST: $searchTarget\r\n" +
        "\r\n")
      .toByteArray(Charsets.UTF_8)

  /**
   * Parses a unicast SSDP search response. Returns null unless it is an HTTP `200` reply carrying
   * both a `LOCATION` (the device description URL) and a `USN` (the unique identity used to dedupe).
   * Header names are case-insensitive; values are trimmed. Tolerates bare-LF framing.
   */
  fun parseResponse(raw: String): SsdpResponse? {
    val lines = raw.replace("\r\n", "\n").split("\n")
    val statusLine = lines.firstOrNull()?.trim() ?: return null
    // Must be a response (HTTP/x 200 ...), not a NOTIFY/M-SEARCH request.
    if (!statusLine.startsWith("HTTP/") || " 200" !in statusLine) return null

    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
      if (line.isBlank()) continue
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val key = line.substring(0, colon).trim().lowercase()
      val value = line.substring(colon + 1).trim()
      // First occurrence wins (RFC header semantics are header-specific; these are single-valued).
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
