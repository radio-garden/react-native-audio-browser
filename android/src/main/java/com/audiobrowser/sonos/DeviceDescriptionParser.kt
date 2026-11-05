package com.audiobrowser.sonos

import org.w3c.dom.Element
import org.w3c.dom.Node

/** A discovered Sonos speaker; its control URLs are absolute. */
internal data class SonosDevice(
  val udn: String,
  val name: String,
  val baseUrl: String,
  val avTransportControlUrl: String,
  val groupRenderingControlUrl: String,
  val zoneGroupTopologyControlUrl: String?,
)

internal object DeviceDescriptionParser {
  private const val SONOS_MANUFACTURER = "Sonos, Inc."
  private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
  private const val GROUP_RENDERING_CONTROL = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
  private const val ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"

  /**
   * Parses a UPnP device description fetched from an SSDP `LOCATION` header, which is [location].
   * Returns null for malformed XML, a non-Sonos device, or one without a UDN, `AVTransport` or
   * `GroupRenderingControl`; `ZoneGroupTopology` is optional. The name is `roomName`, falling back
   * to `friendlyName`.
   */
  fun parse(xml: String, location: String): SonosDevice? {
    val doc = parseSpeakerXml(xml) ?: return null

    // The root <device> is the direct child of <root>, not the first <device> in the document.
    val docElement = doc.documentElement ?: return null
    val root = directChildElement(docElement, "device") ?: return null
    if (!childText(root, "manufacturer").equals(SONOS_MANUFACTURER, ignoreCase = true)) return null
    val udn =
      childText(root, "UDN").ifBlank {
        return null
      }
    val name = childText(root, "roomName").ifBlank { childText(root, "friendlyName") }

    // A <URLBase>, when present, overrides [location] for resolving relative control URLs.
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

  private fun findControlUrl(
    doc: org.w3c.dom.Document,
    serviceType: String,
    baseUrl: String,
  ): String? {
    // Sonos nests its services under embedded devices, so search the whole tree.
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

  private fun childText(parent: Element, tag: String): String =
    directChildElement(parent, tag)?.textContent?.trim() ?: ""

  // Direct children only: embedded devices carry their own manufacturer, UDN and names.
  private fun directChildElement(parent: Element, tag: String): Element? {
    var node: Node? = parent.firstChild
    while (node != null) {
      if (node is Element && node.tagName.equals(tag, ignoreCase = true)) return node
      node = node.nextSibling
    }
    return null
  }
}
