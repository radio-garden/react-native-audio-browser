package com.audiobrowser.destination.sonos

import java.io.ByteArrayInputStream
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory

/** A parsed UPnP SOAP fault (the `<UPnPError>` detail). */
data class SoapFault(val errorCode: Int?, val errorDescription: String?)

/**
 * Pure parse of the UPnP SOAP responses the Sonos backend reads. DOM-based (`javax.xml`), so it is
 * unit-testable on the JVM. Each accessor returns null when the field/response shape is absent.
 */
object SoapResponseParser {
  /** Parses a SOAP fault envelope into its UPnP error, or null when the response is not a fault. */
  fun fault(xml: String): SoapFault? {
    val doc = parse(xml) ?: return null
    return faultOf(doc)
  }

  /** The `<CurrentTransportState>` text from a `GetTransportInfo` response, or null (incl. faults). */
  fun transportState(xml: String): String? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentTransportState")
  }

  /** The `<CurrentVolume>` integer from a `GetVolume` response, or null (incl. faults). */
  fun volume(xml: String): Int? {
    val doc = parse(xml) ?: return null
    if (faultOf(doc) != null) return null
    return firstText(doc, "CurrentVolume")?.toIntOrNull()
  }

  /** Reads a UPnP fault off an already-parsed document, or null when it is not a fault. */
  private fun faultOf(doc: Document): SoapFault? {
    if (!hasElement(doc, "Fault")) return null
    return SoapFault(firstText(doc, "errorCode")?.toIntOrNull(), firstText(doc, "errorDescription"))
  }

  /** True if any element has the given local name, regardless of namespace prefix (`s:Fault`, …). */
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
