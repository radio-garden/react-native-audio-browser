package com.audiobrowser.sonos

import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Runs an SSDP M-SEARCH and returns the raw responses. */
internal interface SsdpScanner {
  fun search(timeoutMs: Int = 3000, repeats: Int = 3): List<SsdpResponse>
}

/**
 * Finds the Sonos rooms on the local network: an SSDP search finds speakers, the first that answers
 * gives the household's group topology, and each group's coordinator gives its control URLs. Finds
 * none when the speakers refuse UPnP (HTTP 403, the user's switch in the Sonos app). Blocking: run
 * off the main thread.
 */
internal class SonosDiscoverer(
  private val httpClient: OkHttpClient,
  private val scanner: SsdpScanner,
) {
  private val soap = SoapClient(httpClient)

  fun discover(timeoutMs: Int = 3000): List<SonosRoom> {
    val locations = scanner.search(timeoutMs).map { it.location }.distinct()
    val groups = locations.firstNotNullOfOrNull { groupsFrom(it) } ?: return emptyList()
    return groups.mapNotNull { group ->
      fetchDevice(group.location)?.let {
        SonosRoom(id = group.coordinator, name = group.name, device = it)
      }
    }
  }

  /**
   * The groups the speaker at [location] reports, or null when it cannot be read or is a hidden
   * speaker, whose view can be missing groups.
   */
  private fun groupsFrom(location: String): List<SonosGroup>? {
    val device = fetchDevice(location) ?: return null
    val controlUrl = device.zoneGroupTopologyControlUrl ?: return null
    return try {
      val xml = soap.execute(controlUrl, SoapEnvelopes.getZoneGroupState())
      if (ZoneGroupState.isHidden(xml, device.udn.removePrefix("uuid:"))) null
      else ZoneGroupState.parse(xml)
    } catch (e: Exception) {
      if (e is SoapException && e.httpCode == 403) {
        Timber.w("Sonos at %s refuses UPnP: it is switched off in the Sonos app", location)
      } else {
        Timber.w(e, "Failed to read the Sonos topology from %s", location)
      }
      null
    }
  }

  /** Fetches and parses a device description, or null on any HTTP or parse failure. */
  fun fetchDevice(location: String): SonosDevice? {
    val xml =
      try {
        httpClient.newCall(Request.Builder().url(location).build()).execute().use { response ->
          if (!response.isSuccessful) return null
          response.body.string()
        }
      } catch (e: Exception) {
        Timber.w(e, "Failed to fetch Sonos device description from %s", location)
        return null
      }
    return DeviceDescriptionParser.parse(xml, location)
  }
}
