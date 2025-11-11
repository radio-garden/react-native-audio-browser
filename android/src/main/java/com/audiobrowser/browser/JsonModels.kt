package com.audiobrowser.browser

import kotlinx.serialization.Serializable
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * JSON serializable models for parsing API responses.
 * These will be converted to Nitro types after parsing.
 */

@Serializable
data class JsonResolvedTrack(
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
    val playable: Boolean? = null,
    val style: String? = null
)

@Serializable
data class JsonTrack(
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
    val playable: Boolean? = null,
    val style: String? = null
)

/**
 * Convert JSON models to Nitro types
 */
private fun String?.toTrackStyle(): TrackStyle? {
    return when (this?.lowercase()) {
        "list" -> TrackStyle.LIST
        "grid" -> TrackStyle.GRID
        else -> null
    }
}

fun JsonResolvedTrack.toNitro(): ResolvedTrack {
    return ResolvedTrack(
        url = url,
        children = children?.map { it.toNitro() }?.toTypedArray(),
        title = title,
        subtitle = subtitle,
        artwork = artwork,
        artist = artist,
        album = album,
        description = description,
        genre = genre,
        duration = duration,
        playable = playable,
        src = src,
        style = style.toTrackStyle()
    )
}

fun JsonTrack.toNitro(): Track {
    return Track(
        url = url,
        title = title,
        subtitle = subtitle,
        artwork = artwork,
        artist = artist,
        album = album,
        description = description,
        genre = genre,
        duration = duration,
        playable = playable,
        src = src,
        style = style.toTrackStyle()
    )
}
