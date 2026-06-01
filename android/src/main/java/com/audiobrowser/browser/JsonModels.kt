package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.CarPlaySiriListButtonPosition
import com.margelo.nitro.audiobrowser.ImageRowItem
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle
import kotlinx.serialization.Serializable

/**
 * JSON serializable models for parsing API responses. These will be converted to Nitro types after
 * parsing.
 */
@Serializable
data class JsonImageRowItem(
  val url: String? = null,
  val artwork: String? = null,
  val title: String,
)

@Serializable
data class JsonResolvedTrack(
  val id: String? = null,
  val url: String,
  val title: String,
  val subtitle: String? = null,
  val icon: String? = null,
  val artwork: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val description: String? = null,
  val genre: String? = null,
  val duration: Double? = null,
  val children: List<JsonTrack>? = null,
  val src: String? = null,
  val style: String? = null,
  val childrenStyle: String? = null,
  val groupTitle: String? = null,
  val live: Boolean? = null,
  val carPlaySiriListButton: String? = null,
)

@Serializable
data class JsonTrack(
  val id: String? = null,
  val url: String? = null,
  val title: String,
  val subtitle: String? = null,
  val icon: String? = null,
  val artwork: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val description: String? = null,
  val genre: String? = null,
  val duration: Double? = null,
  val src: String? = null,
  val style: String? = null,
  val childrenStyle: String? = null,
  val groupTitle: String? = null,
  val live: Boolean? = null,
  val imageRow: List<JsonImageRowItem>? = null,
)

/** Convert JSON models to Nitro types */
private fun String?.toTrackStyle(): TrackStyle? {
  return when (this?.lowercase()) {
    "list" -> TrackStyle.LIST
    "grid" -> TrackStyle.GRID
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

private fun JsonImageRowItem.toNitro(): ImageRowItem {
  return ImageRowItem(
    url = url,
    artwork = artwork,
    artworkSource = null,
    title = title,
  )
}

fun JsonResolvedTrack.toNitro(): ResolvedTrack {
  return ResolvedTrack(
    id = id,
    url = url,
    children = children?.map { it.toNitro() }?.toTypedArray(),
    carPlaySiriListButton = carPlaySiriListButton.toCarPlaySiriListButtonPosition(),
    title = title,
    subtitle = subtitle,
    artwork = artwork,
    artworkSource = null,
    artworkCarPlayTinted = null,
    artist = artist,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    src = src,
    style = style.toTrackStyle(),
    childrenStyle = childrenStyle.toTrackStyle(),
    favorited = null,
    groupTitle = groupTitle,
    live = live,
    imageRow = null,
  )
}

fun JsonTrack.toNitro(): Track {
  return Track(
    id = id,
    url = url,
    title = title,
    subtitle = subtitle,
    artwork = artwork,
    artworkSource = null,
    artworkCarPlayTinted = null,
    artist = artist,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    src = src,
    style = style.toTrackStyle(),
    childrenStyle = childrenStyle.toTrackStyle(),
    favorited = null,
    groupTitle = groupTitle,
    live = live,
    imageRow = imageRow?.map { it.toNitro() }?.toTypedArray(),
  )
}
