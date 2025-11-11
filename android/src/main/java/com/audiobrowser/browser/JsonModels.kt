package com.audiobrowser.browser

import kotlinx.serialization.Serializable
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track

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
    val playable: Boolean? = null
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
    val playable: Boolean? = null
)

/**
 * Convert JSON models to Nitro types
 */
fun JsonResolvedTrack.toNitro(): ResolvedTrack {
    return ResolvedTrack(
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
        children = children?.map { it.toNitro() }?.toTypedArray()
    )
}

fun JsonTrack.toNitro(): Track {
        return Track(
            src = src,
            url = url,
            title = title,
            subtitle = subtitle,
            artwork = artwork,
            artist = artist,
            album = album,
            description = description,
            genre = genre,
            duration = duration,
            playable = playable
        )
}
