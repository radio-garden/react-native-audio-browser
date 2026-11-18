package com.audiobrowser.sonos

import org.w3c.dom.Document

internal data class SoapFault(val errorCode: Int?, val errorDescription: String?)

/**
 * Reads the UPnP SOAP responses the speakers send. Apart from [fault], each reader returns null for
 * a fault and for XML [parseSpeakerXml] refuses; for a field that is absent or empty, [position]
 * nulls that field and the others return null.
 */
internal object SoapResponseParser {
  /** The UPnP error in a fault response, or null when the response is not a fault. */
  fun fault(xml: String): SoapFault? {
    val doc = parse(xml) ?: return null
    return faultOf(doc)
  }

  fun transportState(xml: String): String? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentTransportState")
  }

  fun volume(xml: String): Int? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentVolume")?.toIntOrNull()
  }

  /**
   * The title the speaker reads from the stream (ICY `StreamTitle`, or an HLS stream's ID3), as
   * `r:streamContent` in a `GetPositionInfo` response's `TrackMetaData`.
   */
  fun streamContent(xml: String): String? {
    val metadata = embeddedDocument(xml, "TrackMetaData") ?: return null
    return firstText(metadata, "r:streamContent")
  }

  /**
   * Reads a `GetPositionInfo` response. A time the speaker doesn't report (`NOT_IMPLEMENTED`, or a
   * live stream's `0:00:00` duration) is null.
   */
  fun position(xml: String): SonosPosition? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return SonosPosition(
      streamContent = streamContent(xml),
      positionMs = firstText(doc, "RelTime")?.let(::upnpTimeMs),
      durationMs = firstText(doc, "TrackDuration")?.let(::upnpTimeMs)?.takeIf { it > 0 },
    )
  }

  fun currentUri(xml: String): String? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentURI")
  }

  private fun upnpTimeMs(time: String): Long? {
    val parts = time.split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    return (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
  }

  /** Parses the text of [tag], an XML document escaped inside the SOAP response. */
  internal fun embeddedDocument(xml: String, tag: String): Document? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return parse(firstText(doc, tag) ?: return null)
  }

  private fun faultOf(doc: Document): SoapFault? {
    if (!hasElement(doc, "Fault")) return null
    return SoapFault(firstText(doc, "errorCode")?.toIntOrNull(), firstText(doc, "errorDescription"))
  }

  // The parser is not namespace-aware, so a name keeps its prefix (`s:Fault`).
  private fun hasElement(doc: Document, localName: String): Boolean {
    val all = doc.getElementsByTagName("*")
    for (i in 0 until all.length) {
      val tag = all.item(i).nodeName
      if (tag == localName || tag.endsWith(":$localName")) return true
    }
    return false
  }

  private fun parse(xml: String): Document? = parseSpeakerXml(xml)

  private fun firstText(doc: Document, tag: String): String? {
    val nodes = doc.getElementsByTagName(tag)
    if (nodes.length == 0) return null
    return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
  }
}
