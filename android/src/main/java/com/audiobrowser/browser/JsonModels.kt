package com.audiobrowser.browser

import kotlinx.serialization.Serializable
import com.margelo.nitro.audiobrowser.BrowserList
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.BrowserItemStyle

/**
 * JSON serializable models for parsing API responses.
 * These will be converted to Nitro types after parsing.
 */

@Serializable
data class JsonBrowserList(
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
    val children: List<JsonTrack>,
    val style: String? = null,
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
fun JsonBrowserList.toNitro(): BrowserList {
    return BrowserList(
        children = children.map { it.toNitro() }.toTypedArray(),
        style = style?.let { 
            when (it.lowercase()) {
                "grid" -> BrowserItemStyle.GRID
                else -> BrowserItemStyle.LIST
            }
        },
        playable = playable,
        url = url,
        title = title,
        subtitle = subtitle,
        icon = icon,
        artwork = artwork,
        artist = artist,
        album = album,
        description = description,
        genre = genre,
        duration = duration
    )
}

fun JsonTrack.toNitro(): Track {
        return Track(
            src = src,
            url = url,
            title = title,
            subtitle = subtitle,
            icon = icon,
            artwork = artwork,
            artist = artist,
            album = album,
            description = description,
            genre = genre,
            duration = duration,
            playable = playable
        )
}
