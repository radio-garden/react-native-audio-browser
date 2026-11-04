package com.audiobrowser.sonos

import java.util.Locale

/** A UPnP SOAP request: the `SOAPACTION` header value (service#action) and the XML body. */
internal data class SoapAction(val soapAction: String, val body: String)

/**
 * Builds the SOAP request bodies + SOAPAction headers for the UPnP actions the Sonos backend
 * issues. Bodies are deterministic and entity-escaped; the DIDL metadata is escaped a second time
 * because it is embedded as element text inside `CurrentURIMetaData` (UPnP requires the nested
 * document be entity-encoded). All actions use `InstanceID` 0.
 */
internal object SoapEnvelopes {
  const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
  const val GROUP_RENDERING_CONTROL = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
  const val ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"

  fun setAvTransportUri(currentUri: String, metadata: String): SoapAction =
    action(
      AV_TRANSPORT,
      "SetAVTransportURI",
      "<InstanceID>0</InstanceID>" +
        "<CurrentURI>${xmlEscape(currentUri)}</CurrentURI>" +
        "<CurrentURIMetaData>${xmlEscape(metadata)}</CurrentURIMetaData>",
    )

  fun play(): SoapAction =
    action(AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

  fun pause(): SoapAction = action(AV_TRANSPORT, "Pause", "<InstanceID>0</InstanceID>")

  fun stop(): SoapAction = action(AV_TRANSPORT, "Stop", "<InstanceID>0</InstanceID>")

  /** Seeks within the current track, to [positionMs] rounded down to the second. */
  fun seek(positionMs: Long): SoapAction {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    val target =
      String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    return action(
      AV_TRANSPORT,
      "Seek",
      "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>",
    )
  }

  fun getTransportInfo(): SoapAction =
    action(AV_TRANSPORT, "GetTransportInfo", "<InstanceID>0</InstanceID>")

  fun getPositionInfo(): SoapAction =
    action(AV_TRANSPORT, "GetPositionInfo", "<InstanceID>0</InstanceID>")

  fun getZoneGroupState(): SoapAction = action(ZONE_GROUP_TOPOLOGY, "GetZoneGroupState", "")

  /** The volume of the coordinator's whole group, which is the room's own when it plays alone. */
  fun setGroupVolume(volume: Int): SoapAction =
    action(
      GROUP_RENDERING_CONTROL,
      "SetGroupVolume",
      "<InstanceID>0</InstanceID><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
    )

  fun getGroupVolume(): SoapAction =
    action(GROUP_RENDERING_CONTROL, "GetGroupVolume", "<InstanceID>0</InstanceID>")

  private fun action(service: String, name: String, args: String): SoapAction =
    SoapAction(
      soapAction = "$service#$name",
      body =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
          "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
          "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
          "<s:Body>" +
          "<u:$name xmlns:u=\"$service\">$args</u:$name>" +
          "</s:Body></s:Envelope>",
    )
}

/** [s] as XML text or attribute content. */
internal fun xmlEscape(s: String): String =
  s.replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    // &#39; rather than &apos;, which some older XML readers, older Sonos firmware among them,
    // mishandle.
    .replace("'", "&#39;")
