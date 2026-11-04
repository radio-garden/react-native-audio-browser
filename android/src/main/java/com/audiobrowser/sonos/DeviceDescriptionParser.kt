package com.audiobrowser.sonos

import org.w3c.dom.Element
import org.w3c.dom.Node

/** A discovered Sonos speaker: its identity, its name, and the absolute control URLs. */
internal data class SonosDevice(
  val udn: String,
  val name: String,
  val baseUrl: String,
  val avTransportControlUrl: String,
  val groupRenderingControlUrl: String,
  val zoneGroupTopologyControlUrl: String?,
)

/**
 * Parses a UPnP `device_description.xml` (fetched from an SSDP `LOCATION`) into a [SonosDevice].
 * Sonos nests its services under embedded devices, so the whole device tree is searched. Returns
 * null for a non-Sonos device, malformed XML, or a description without `AVTransport` and
 * `GroupRenderingControl`; `ZoneGroupTopology` is optional.
 */
internal object DeviceDescriptionParser {
  private const val SONOS_MANUFACTURER = "Sonos, Inc."
  private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
  private const val GROUP_RENDERING_CONTROL = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
  private const val ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"

  fun parse(xml: String, location: String): SonosDevice? {
    val doc = parseSpeakerXml(xml) ?: return null

    // From the root <device>, the direct child of <root>, not the first <device> in the document.
    val docElement = doc.documentElement ?: return null
    val root = directChildElement(docElement, "device") ?: return null
    if (!childText(root, "manufacturer").equals(SONOS_MANUFACTURER, ignoreCase = true)) return null
    val udn =
      childText(root, "UDN").ifBlank {
        return null
      }
    val name = childText(root, "roomName").ifBlank { childText(root, "friendlyName") }

    // UPnP allows an explicit <URLBase>; when present it overrides the retrieval location for
    // resolving relative control URLs.
    val baseUrl =
      childText(docElement, "URLBase").ifBlank { "" }.trimEnd('/').ifBlank { baseUrlOf(location) }
        ?: return null
    val avControl = findControlUrl(doc, AV_TRANSPORT, baseUrl) ?: return null
    val groupRcControl = findControlUrl(doc, GROUP_RENDERING_CONTROL, baseUrl) ?: return null

    return SonosDevice(
      udn = udn,
      name = name,
      baseUrl = baseUrl,
      avTransportControlUrl = avControl,
      groupRenderingControlUrl = groupRcControl,
      zoneGroupTopologyControlUrl = findControlUrl(doc, ZONE_GROUP_TOPOLOGY, baseUrl),
    )
  }

  /**
   * Finds the first `<service>` anywhere in the tree with [serviceType] and returns its absolute
   * control URL, or null if absent.
   */
  private fun findControlUrl(
    doc: org.w3c.dom.Document,
    serviceType: String,
    baseUrl: String,
  ): String? {
    val services = doc.getElementsByTagName("service")
    for (i in 0 until services.length) {
      val service = services.item(i) as? Element ?: continue
      if (childText(service, "serviceType").equals(serviceType, ignoreCase = true)) {
        val control =
          childText(service, "controlURL").ifBlank {
            return null
          }
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

  /**
   * Direct-child element text by tag name (first match), or "" — avoids matching nested devices.
   */
  private fun childText(parent: Element, tag: String): String =
    directChildElement(parent, tag)?.textContent?.trim() ?: ""

  /** The first direct-child [Element] with the given tag name, or null. */
  private fun directChildElement(parent: Element, tag: String): Element? {
    var node: Node? = parent.firstChild
    while (node != null) {
      if (node is Element && node.tagName.equals(tag, ignoreCase = true)) return node
      node = node.nextSibling
    }
    return null
  }
}
