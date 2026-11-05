package com.audiobrowser.sonos

import org.w3c.dom.Element

/** Sonos rooms playing together, controlled through their [coordinator]. */
internal data class SonosGroup(
  /** The coordinator's `RINCON_…` UUID. */
  val coordinator: String,
  /** The coordinator's device description URL. */
  val location: String,
  val name: String,
)

/** A [SonosGroup], with its coordinator's control URLs: what a route stands for. */
internal data class SonosRoom(val id: String, val name: String, val device: SonosDevice)

/**
 * Reads a `ZoneGroupTopology.GetZoneGroupState` response into the groups a user can play to, each
 * listed once at its coordinator: the only member that accepts transport commands. The hidden half
 * of a stereo pair answers SSDP but refuses them. A group of several rooms is named as the Sonos
 * app names it: `Kitchen + 2`.
 */
internal object ZoneGroupState {
  /**
   * Whether [uuid] is reported hidden (the hidden half of a stereo pair), whose own topology can
   * leave out its pair's group. False when [xml] cannot be read.
   */
  fun isHidden(xml: String, uuid: String): Boolean {
    val doc = SoapResponseParser.embeddedDocument(xml, "ZoneGroupState") ?: return false
    val members = doc.getElementsByTagName("ZoneGroupMember")
    return (0 until members.length)
      .map { members.item(it) as Element }
      .any { it.getAttribute("UUID") == uuid && it.getAttribute("Invisible") == "1" }
  }

  /**
   * The groups, or null when [xml] holds no readable `ZoneGroupState`. Skips a group whose
   * coordinator is missing or hidden.
   */
  fun parse(xml: String): List<SonosGroup>? {
    val doc = SoapResponseParser.embeddedDocument(xml, "ZoneGroupState") ?: return null
    val groups = doc.getElementsByTagName("ZoneGroup")
    return (0 until groups.length).mapNotNull { groupOf(groups.item(it) as Element) }
  }

  private fun groupOf(group: Element): SonosGroup? {
    // Direct children only: a home theatre's satellites nest inside a member and are not rooms.
    val members =
      (0 until group.childNodes.length)
        .map { group.childNodes.item(it) }
        .filterIsInstance<Element>()
        .filter { it.tagName == "ZoneGroupMember" }
    val coordinator =
      members.firstOrNull { it.getAttribute("UUID") == group.getAttribute("Coordinator") }
        ?: return null
    if (coordinator.getAttribute("Invisible") == "1") return null
    val name = coordinator.getAttribute("ZoneName")
    val otherRooms =
      members
        .filter { it.getAttribute("Invisible") != "1" }
        .map { it.getAttribute("ZoneName") }
        .distinct()
        .count { it != name }
    return SonosGroup(
      coordinator = coordinator.getAttribute("UUID"),
      location = coordinator.getAttribute("Location"),
      name = if (otherRooms == 0) name else "$name + $otherRooms",
    )
  }
}
