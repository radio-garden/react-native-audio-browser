package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.CarPlaySiriListButtonPosition
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackRequest
import com.margelo.nitro.audiobrowser.TrackStyle
import kotlinx.serialization.Serializable

/**
 * JSON serializable models for parsing API responses. These will be converted to Nitro types after
 * parsing. Legacy `groupTitle`/`imageRow` keys in payloads are simply unknown to these models and
 * decode as ignored dead weight (ADR 0010).
 */

/** JSON model for a track's per-request HTTP override (identity/auth/signed-url). */
@Serializable
data class JsonTrackRequest(
  val userAgent: String? = null,
  val headers: Map<String, String>? = null,
  val query: Map<String, String>? = null,
) {
  fun toNitro(): TrackRequest = TrackRequest(userAgent, headers, query)
}

/** JSON model for a page section (ADR 0010). */
@Serializable
data class JsonSection(
  val title: String? = null,
  val subtitle: String? = null,
  val style: String? = null,
  val path: String? = null,
  val children: List<JsonTrack>,
)

@Serializable
data class JsonResolvedTrack(
  val id: String? = null,
  val path: String,
  val title: String,
  val subtitle: String? = null,
  val icon: String? = null,
  val artwork: JsonArtwork? = null,
  val artist: String? = null,
  val albumPath: String? = null,
  val album: String? = null,
  val description: String? = null,
  val genre: String? = null,
  val duration: Double? = null,
  val sections: List<JsonSection>? = null,
  val children: List<JsonTrack>? = null,
  val src: String? = null,
  val style: String? = null,
  val childrenStyle: String? = null,
  val live: Boolean? = null,
  val carPlaySiriListButton: String? = null,
)

@Serializable
data class JsonTrack(
  val id: String? = null,
  val path: String? = null,
  val title: String,
  val subtitle: String? = null,
  val icon: String? = null,
  val artwork: JsonArtwork? = null,
  val artist: String? = null,
  val albumPath: String? = null,
  val album: String? = null,
  val description: String? = null,
  val genre: String? = null,
  val duration: Double? = null,
  val src: String? = null,
  val request: JsonTrackRequest? = null,
  val style: String? = null,
  val childrenStyle: String? = null,
  val live: Boolean? = null,
)

/** Convert JSON models to Nitro types */
private fun String?.toTrackStyle(): TrackStyle? {
  return when (this?.lowercase()) {
    "list" -> TrackStyle.LIST
    "grid" -> TrackStyle.GRID
    else -> null
  }
}

private fun String?.toSectionStyle(): SectionStyle? {
  return when (this?.lowercase()) {
    "list" -> SectionStyle.LIST
    "grid" -> SectionStyle.GRID
    "rail" -> SectionStyle.RAIL
    else -> null
  }
}

private fun String?.toCarPlaySiriListButtonPosition(): CarPlaySiriListButtonPosition? {
  return when (this?.lowercase()) {
    "top" -> CarPlaySiriListButtonPosition.TOP
    "bottom" -> CarPlaySiriListButtonPosition.BOTTOM
    else -> null
  }
}

fun JsonSection.toNitro(): Section {
  return Section(
    title = title,
    subtitle = subtitle,
    style = style.toSectionStyle(),
    path = path,
    children = children.map { it.toNitro() }.toTypedArray(),
  )
}

fun JsonResolvedTrack.toNitro(): ResolvedTrack {
  return ResolvedTrack(
    id = id,
    path = path,
    sections = sections?.map { it.toNitro() }?.toTypedArray(),
    children = children?.map { it.toNitro() }?.toTypedArray(),
    carPlaySiriListButton = carPlaySiriListButton.toCarPlaySiriListButtonPosition(),
    title = title,
    subtitle = subtitle,
    artwork = artwork?.toNitro(),
    artworkSource = null,
    // Containers carry no per-track media-request override (mirrors iOS JsonResolvedTrack).
    request = null,
    artworkCarPlayTinted = null,
    artist = artist,
    albumPath = albumPath,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    src = src,
    style = style.toTrackStyle(),
    childrenStyle = childrenStyle.toTrackStyle(),
    favorited = null,
    live = live,
  )
}

fun JsonTrack.toNitro(): Track {
  return Track(
    id = id,
    path = path,
    title = title,
    subtitle = subtitle,
    artwork = artwork?.toNitro(),
    artworkSource = null,
    request = request?.toNitro(),
    artworkCarPlayTinted = null,
    artist = artist,
    albumPath = albumPath,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    src = src,
    style = style.toTrackStyle(),
    childrenStyle = childrenStyle.toTrackStyle(),
    favorited = null,
    live = live,
  )
}
