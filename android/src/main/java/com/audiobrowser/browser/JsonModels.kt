package com.audiobrowser.browser

import com.audiobrowser.util.toArtworkRendering
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
)

/**
 * Convert JSON models to Nitro types.
 *
 * `style` rides the models as a raw [JsonElement] and maps leniently here — tolerant decoding by
 * design (ADR 0011): a stale value (the retired string vocabulary), a wrong-typed field, or an
 * unknown enum value is "no declaration", never a parse failure. A typed field would fail the whole
 * page, since this decoder is otherwise strict.
 */
private fun JsonObject.stringField(key: String): String? =
  (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A strict JSON boolean — a quoted "false" is a wrong-typed field, i.e. no declaration. */
private fun JsonObject.booleanField(key: String): Boolean? =
  (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/**
 * A block that resolves to no declarations at all — `{}` or all-unknown values — maps to null, so
 * "no declaration" has one shape on every platform (iOS makes the same collapse).
 */
private fun JsonElement?.toTrackStyle(): TrackStyle? =
  (this as? JsonObject)?.let { obj ->
    val display = obj.stringField("display").toStyleDisplay()
    val artworkRendering = obj.stringField("artworkRendering").toArtworkRendering()
    val imageShape = obj.stringField("imageShape").toImageShape()
    // 'none' is a legitimate accessory value (the inheritance escape); only
    // emptiness collapses.
    val accessorySymbol = obj.stringField("accessorySymbol")?.takeIf { it.isNotEmpty() }
    if (
      display == null && artworkRendering == null && imageShape == null && accessorySymbol == null
    )
      null
    else
      TrackStyle(
        display = display,
        artworkRendering = artworkRendering,
        imageShape = imageShape,
        accessorySymbol = accessorySymbol,
      )
  }

private fun JsonElement?.toSectionStyle(): SectionStyle? =
  (this as? JsonObject)?.let { obj ->
    val display = obj.stringField("display").toStyleDisplay()
    val artworkRendering = obj.stringField("artworkRendering").toArtworkRendering()
    val imageShape = obj.stringField("imageShape").toImageShape()
    val accessorySymbol = obj.stringField("accessorySymbol")?.takeIf { it.isNotEmpty() }
    val gridWrap = obj.booleanField("gridWrap")
    if (
      display == null &&
        artworkRendering == null &&
        imageShape == null &&
        accessorySymbol == null &&
        gridWrap == null
    )
      null
    else
      SectionStyle(
        display = display,
        artworkRendering = artworkRendering,
        imageShape = imageShape,
        accessorySymbol = accessorySymbol,
        gridWrap = gridWrap,
      )
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
  )
}
