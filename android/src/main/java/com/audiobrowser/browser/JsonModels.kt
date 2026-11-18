package com.audiobrowser.browser

import com.audiobrowser.util.EMPTY_SECTION_STYLE
import com.audiobrowser.util.EMPTY_TRACK_STYLE
import com.audiobrowser.util.toArtworkRendering
import com.audiobrowser.util.toCardImage
import com.audiobrowser.util.toGridTile
import com.audiobrowser.util.toImageShape
import com.audiobrowser.util.toStyleDisplay
import com.margelo.nitro.audiobrowser.CarPlaySiriListButtonPosition
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackRequest
import com.margelo.nitro.audiobrowser.TrackStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class JsonTrackRequest(
  val userAgent: String? = null,
  val headers: Map<String, String>? = null,
  val query: Map<String, String>? = null,
) {
  fun toNitro(): TrackRequest = TrackRequest(userAgent, headers, query)
}

@Serializable
data class JsonSection(
  val title: String? = null,
  val style: JsonElement? = null,
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
  val style: JsonElement? = null,
  val disabled: Boolean? = null,
  val live: Boolean? = null,
  val icyCharset: String? = null,
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
  val style: JsonElement? = null,
  val disabled: Boolean? = null,
  val live: Boolean? = null,
  val icyCharset: String? = null,
)

// `style` stays a raw JsonElement and maps leniently here (ADR 0011): a stale, wrong-typed or
// unknown value is no declaration. A typed field would fail the whole page, as the decoder is
// otherwise strict.
private fun JsonObject.stringField(key: String): String? =
  (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

// booleanOrNull alone accepts a quoted "false"; that is wrong-typed, so no declaration.
private fun JsonObject.booleanField(key: String): Boolean? =
  (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

// A block with no declarations maps to null, as on iOS, so "no declaration" has one shape.
private fun JsonElement?.toTrackStyle(): TrackStyle? =
  (this as? JsonObject)?.let { obj ->
    TrackStyle(
        display = obj.stringField("display").toStyleDisplay(),
        artworkRendering = obj.stringField("artworkRendering").toArtworkRendering(),
        imageShape = obj.stringField("imageShape").toImageShape(),
        // 'none' is a real value (the inheritance escape); only emptiness collapses.
        accessorySymbol = obj.stringField("accessorySymbol")?.takeIf { it.isNotEmpty() },
        cardTint = obj.stringField("cardTint")?.takeIf { it.isNotEmpty() },
        cardImage = obj.stringField("cardImage").toCardImage(),
      )
      .takeIf { it != EMPTY_TRACK_STYLE }
  }

private fun JsonElement?.toSectionStyle(): SectionStyle? =
  (this as? JsonObject)?.let { obj ->
    SectionStyle(
        display = obj.stringField("display").toStyleDisplay(),
        artworkRendering = obj.stringField("artworkRendering").toArtworkRendering(),
        imageShape = obj.stringField("imageShape").toImageShape(),
        accessorySymbol = obj.stringField("accessorySymbol")?.takeIf { it.isNotEmpty() },
        cardTint = obj.stringField("cardTint")?.takeIf { it.isNotEmpty() },
        cardImage = obj.stringField("cardImage").toCardImage(),
        gridWrap = obj.booleanField("gridWrap"),
        gridTile = obj.stringField("gridTile").toGridTile(),
      )
      .takeIf { it != EMPTY_SECTION_STYLE }
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
    // Containers carry no per-track request override, as on iOS.
    request = null,
    artist = artist,
    albumPath = albumPath,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    src = src,
    style = style.toSectionStyle(),
    disabled = disabled,
    favorited = null,
    live = live,
    icyCharset = icyCharset,
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
    artist = artist,
    albumPath = albumPath,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    src = src,
    style = style.toTrackStyle(),
    disabled = disabled,
    favorited = null,
    live = live,
    icyCharset = icyCharset,
  )
}
