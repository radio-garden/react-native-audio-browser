package com.audiobrowser.destination.sonos

/** A UPnP SOAP request: the `SOAPACTION` header value (service#action) and the XML body. */
data class SoapAction(val soapAction: String, val body: String)

/**
 * Builds the SOAP request bodies + SOAPAction headers for the UPnP actions the Sonos backend issues.
 * Bodies are deterministic and entity-escaped; the DIDL metadata is escaped a second time because it
 * is embedded as element text inside `CurrentURIMetaData` (UPnP requires the nested document be
 * entity-encoded). All actions use `InstanceID` 0 and the `Master` channel.
 */
object SoapEnvelopes {
  const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
  const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

  fun setAvTransportUri(currentUri: String, metadata: String): SoapAction =
    action(
      AV_TRANSPORT,
      "SetAVTransportURI",
      "<InstanceID>0</InstanceID>" +
        "<CurrentURI>${escape(currentUri)}</CurrentURI>" +
        "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>",
    )

  fun play(): SoapAction =
    action(AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

  fun pause(): SoapAction = action(AV_TRANSPORT, "Pause", "<InstanceID>0</InstanceID>")

  fun stop(): SoapAction = action(AV_TRANSPORT, "Stop", "<InstanceID>0</InstanceID>")

  fun getTransportInfo(): SoapAction =
    action(AV_TRANSPORT, "GetTransportInfo", "<InstanceID>0</InstanceID>")

  fun setVolume(volume: Int): SoapAction =
    action(
      RENDERING_CONTROL,
      "SetVolume",
      "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
        "<DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
    )

  fun getVolume(): SoapAction =
    action(
      RENDERING_CONTROL,
      "GetVolume",
      "<InstanceID>0</InstanceID><Channel>Master</Channel>",
    )

  fun setMute(mute: Boolean): SoapAction =
    action(
      RENDERING_CONTROL,
      "SetMute",
      "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
        "<DesiredMute>${if (mute) 1 else 0}</DesiredMute>",
    )

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

  private fun escape(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
}
