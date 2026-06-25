package com.audiobrowser.destination.sonos

import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Runs an SSDP M-SEARCH and returns the raw responses. Abstracted so discovery is testable. */
interface SsdpScanner {
  fun search(timeoutMs: Int = 3000, repeats: Int = 3): List<SsdpResponse>
}

/**
 * Discovers Sonos devices on the local network: an SSDP scan yields candidate `LOCATION`s, each
 * device description is fetched + parsed via [DeviceDescriptionParser], and the results are deduped
 * by UDN (a single Sonos answers an M-SEARCH several times). Blocking — run off the main thread.
 */
class SonosDiscoverer(
  private val httpClient: OkHttpClient,
  private val scanner: SsdpScanner,
) {
  fun discover(timeoutMs: Int = 3000): List<SonosDevice> {
    // Dedup candidate descriptions by LOCATION first to avoid fetching the same device repeatedly.
    val locations = scanner.search(timeoutMs).map { it.location }.distinct()
    val byUdn = LinkedHashMap<String, SonosDevice>()
    for (location in locations) {
      val device = fetchDevice(location) ?: continue
      byUdn.putIfAbsent(device.udn, device)
    }
    return byUdn.values.toList()
  }

  /** Fetches and parses a single device description URL, or null on any HTTP/parse failure. */
  fun fetchDevice(location: String): SonosDevice? {
    val xml =
      try {
        httpClient.newCall(Request.Builder().url(location).build()).execute().use { response ->
          if (!response.isSuccessful) return null
          response.body?.string() ?: return null
        }
      } catch (t: Throwable) {
        Timber.w(t, "Failed to fetch Sonos device description from %s", location)
        return null
      }
    return DeviceDescriptionParser.parse(xml, location)
  }
}
