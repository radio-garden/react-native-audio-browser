package com.audiobrowser.destination.sonos

import java.io.ByteArrayInputStream
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A discovered Sonos speaker, reduced to what control needs: its identity, a display name, and the
 * absolute control URLs for the two UPnP services we drive.
 */
data class SonosDevice(
  val udn: String,
  val name: String,
  val baseUrl: String,
  val avTransportControlUrl: String,
  val renderingControlControlUrl: String,
)

/**
 * Pure parse of a UPnP `device_description.xml` (fetched from an SSDP `LOCATION`) into a
 * [SonosDevice]. Uses DOM (`javax.xml`, available on both the JVM and Android) so it is unit-testable
 * without Robolectric.
 *
 * Sonos nests the `AVTransport` and `RenderingControl` services under an embedded `MediaRenderer`
 * device, so the whole device tree is searched. Returns null for a non-Sonos device, a description
 * missing either required service, or malformed XML.
 */
object DeviceDescriptionParser {
  private const val SONOS_MANUFACTURER = "Sonos, Inc."
  private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
  private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

  fun parse(xml: String, location: String): SonosDevice? {
    val doc =
      runCatching {
          val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
          factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }
        .getOrNull() ?: return null

    val devices = doc.getElementsByTagName("device")
    if (devices.length == 0) return null

    // Identity + Sonos check + room name come from the root <device>.
    val root = devices.item(0) as? Element ?: return null
    if (!childText(root, "manufacturer").equals(SONOS_MANUFACTURER, ignoreCase = true)) return null
    val udn = childText(root, "UDN").ifBlank { return null }
    // Prefer the Sonos room name; fall back to friendlyName.
    val name = childText(root, "roomName").ifBlank { childText(root, "friendlyName") }

    val baseUrl = baseUrlOf(location) ?: return null
    val avControl = findControlUrl(doc, AV_TRANSPORT, baseUrl) ?: return null
    val rcControl = findControlUrl(doc, RENDERING_CONTROL, baseUrl) ?: return null

    return SonosDevice(
      udn = udn,
      name = name,
      baseUrl = baseUrl,
      avTransportControlUrl = avControl,
      renderingControlControlUrl = rcControl,
    )
  }

  /** Finds the first `<service>` anywhere in the tree with [serviceType] and returns its absolute
   *  control URL, or null if absent. */
  private fun findControlUrl(doc: org.w3c.dom.Document, serviceType: String, baseUrl: String): String? {
    val services = doc.getElementsByTagName("service")
    for (i in 0 until services.length) {
      val service = services.item(i) as? Element ?: continue
      if (childText(service, "serviceType").equals(serviceType, ignoreCase = true)) {
        val control = childText(service, "controlURL").ifBlank { return null }
        return resolveUrl(baseUrl, control)
      }
    }
    return null
  }

  /** The scheme://host:port prefix of a URL, or null if it has none. */
  private fun baseUrlOf(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return null
    val pathStart = url.indexOf('/', schemeEnd + 3)
    return if (pathStart < 0) url else url.substring(0, pathStart)
  }

  private fun resolveUrl(baseUrl: String, control: String): String =
    when {
      control.startsWith("http://", ignoreCase = true) ||
        control.startsWith("https://", ignoreCase = true) -> control
      control.startsWith("/") -> baseUrl + control
      else -> "$baseUrl/$control"
    }

  /** Direct-child element text by tag name (first match), or "" — avoids matching nested devices. */
  private fun childText(parent: Element, tag: String): String {
    var node: Node? = parent.firstChild
    while (node != null) {
      if (node is Element && node.tagName.equals(tag, ignoreCase = true)) {
        return node.textContent.trim()
      }
      node = node.nextSibling
    }
    return ""
  }
}
