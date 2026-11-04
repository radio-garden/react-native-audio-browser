package com.audiobrowser.sonos

/**
 * Builds the DIDL-Lite metadata document Sonos wants as `CurrentURIMetaData` in
 * `SetAVTransportURI`. Output is deterministic (fixed namespace and field order) and XML-escaped;
 * the SOAP layer escapes it again when embedding it in the envelope. Empty/blank optional fields
 * are omitted.
 */
internal object DidlLite {
  private const val HEADER =
    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"

  fun build(
    title: String,
    creator: String? = null,
    album: String? = null,
    albumArtUri: String? = null,
    live: Boolean = true,
  ): String {
    val upnpClass =
      if (live) "object.item.audioItem.audioBroadcast" else "object.item.audioItem.musicTrack"
    return buildString {
      append(HEADER)
      append("<item id=\"R:0/0/0\" parentID=\"R:0/0\" restricted=\"true\">")
      append("<dc:title>").append(xmlEscape(title)).append("</dc:title>")
      append("<upnp:class>").append(upnpClass).append("</upnp:class>")
      appendElement("dc:creator", creator)
      appendElement("upnp:album", album)
      appendElement("upnp:albumArtURI", albumArtUri)
      append("</item></DIDL-Lite>")
    }
  }

  private fun StringBuilder.appendElement(tag: String, value: String?) {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return
    append("<").append(tag).append(">").append(xmlEscape(v)).append("</").append(tag).append(">")
  }
}
