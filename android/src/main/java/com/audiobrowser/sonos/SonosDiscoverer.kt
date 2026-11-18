package com.audiobrowser.sonos

import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Runs an SSDP M-SEARCH and returns the replies, one per USN. Blocks; never throws. */
internal interface SsdpScanner {
  fun search(timeoutMs: Int = 3000, repeats: Int = 3): List<SsdpResponse>
}

internal class SonosDiscoverer(
  private val httpClient: OkHttpClient,
  private val scanner: SsdpScanner,
) {
  private val soap = SoapClient(httpClient)

  /**
   * Blocks. Any one speaker's topology lists the household's groups, each found at its coordinator;
   * empty when none gives it, as when UPnP is switched off on the speakers (HTTP 403).
   */
  fun discover(timeoutMs: Int = 3000): List<SonosRoom> {
    val locations = scanner.search(timeoutMs).map { it.location }.distinct()
    val groups = locations.firstNotNullOfOrNull { groupsFrom(it) } ?: return emptyList()
    return groups.mapNotNull { group ->
      fetchDevice(group.location)?.let {
        SonosRoom(id = group.coordinator, name = group.name, device = it)
      }
    }
  }

  private fun groupsFrom(location: String): List<SonosGroup>? {
    val device = fetchDevice(location) ?: return null
    val controlUrl = device.zoneGroupTopologyControlUrl ?: return null
    return try {
      val xml = soap.execute(controlUrl, SoapEnvelopes.getZoneGroupState())
      // The hidden half of a stereo pair can leave its own pair out: ask the next speaker.
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

  fun fetchDevice(location: String): SonosDevice? {
    val xml =
      try {
        httpClient.newCall(Request.Builder().url(location).build()).execute().use { response ->
          if (!response.isSuccessful) return null
          response.speakerBody()
        }
      } catch (e: Exception) {
        Timber.w(e, "Failed to fetch Sonos device description from %s", location)
        return null
      }
    return DeviceDescriptionParser.parse(xml, location)
  }
}
