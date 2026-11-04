package com.audiobrowser.sonos

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/** A parsed UPnP SOAP fault (the `<UPnPError>` detail). */
internal data class SoapFault(val errorCode: Int?, val errorDescription: String?)

/** Parses the UPnP SOAP responses the speakers send. Each returns null when its field is absent. */
internal object SoapResponseParser {
  /** Parses a SOAP fault envelope into its UPnP error, or null when the response is not a fault. */
  fun fault(xml: String): SoapFault? {
    val doc = parse(xml) ?: return null
    return faultOf(doc)
  }

  /**
   * The `<CurrentTransportState>` text from a `GetTransportInfo` response, or null (incl. faults).
   */
  fun transportState(xml: String): String? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentTransportState")
  }

  /** The `<CurrentVolume>` integer from a `GetGroupVolume` response, or null (incl. faults). */
  fun volume(xml: String): Int? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentVolume")?.toIntOrNull()
  }

  /**
   * The title the speaker reads from the stream (ICY `StreamTitle`, or an HLS stream's ID3), as
   * `r:streamContent` in a `GetPositionInfo` response's `TrackMetaData`; null when there is none.
   */
  fun streamContent(xml: String): String? {
    val metadata = embeddedDocument(xml, "TrackMetaData") ?: return null
    return firstText(metadata, "r:streamContent")
  }

  /**
   * The position, duration and stream title from a `GetPositionInfo` response, or null (incl.
   * faults). A time the speaker doesn't report (`NOT_IMPLEMENTED`, or a live stream's empty
   * duration) is null.
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

  /** The `<CurrentURI>` from a `GetMediaInfo` response, or null (incl. faults). */
  fun currentUri(xml: String): String? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentURI")
  }

  /** `H+:MM:SS` (with optional fractions) in milliseconds, or null for anything else. */
  private fun upnpTimeMs(time: String): Long? {
    val parts = time.split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    return (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
  }

  /** The text of a SOAP response's [tag], itself an XML document escaped inside it. */
  internal fun embeddedDocument(xml: String, tag: String): Document? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return parse(firstText(doc, tag) ?: return null)
  }

  /** Reads a UPnP fault off an already-parsed document, or null when it is not a fault. */
  private fun faultOf(doc: Document): SoapFault? {
    if (!hasElement(doc, "Fault")) return null
    return SoapFault(firstText(doc, "errorCode")?.toIntOrNull(), firstText(doc, "errorDescription"))
  }

  /**
   * True if any element has the given local name, regardless of namespace prefix (`s:Fault`, …).
   */
  private fun hasElement(doc: Document, localName: String): Boolean {
    val all = doc.getElementsByTagName("*")
    for (i in 0 until all.length) {
      val tag = all.item(i).nodeName
      if (tag == localName || tag.endsWith(":$localName")) return true
    }
    return false
  }

  private fun parse(xml: String): Document? =
    runCatching {
        DocumentBuilderFactory.newInstance()
          .apply { isNamespaceAware = false }
          .newDocumentBuilder()
          .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
      }
      .getOrNull()

  private fun firstText(doc: Document, tag: String): String? {
    val nodes = doc.getElementsByTagName(tag)
    if (nodes.length == 0) return null
    return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
  }
}
