package com.doublesymmetry.trackplayer.model

import com.margelo.nitro.audiobrowser.AudioMetadata

/** Common metadata that can be received from media sources. */
data class CommonMetadata(
    val title: String? = null,
    val artist: String? = null,
    val albumTitle: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val artworkUri: String? = null,
    val trackNumber: String? = null,
    val composer: String? = null,
    val conductor: String? = null,
    val genre: String? = null,
    val compilation: String? = null,
    val station: String? = null,
    val mediaType: String? = null,
    val creationDate: String? = null,
    val creationYear: String? = null,
    val url: String? = null
) {
    fun toNitro(): AudioMetadata {
        return AudioMetadata(
            title = title,
            artist = artist,
            albumTitle = albumTitle,
            subtitle = subtitle,
            description = description,
            artworkUri = artworkUri,
            trackNumber = trackNumber,
            composer = composer,
            conductor = conductor,
            genre = genre,
            compilation = compilation,
            station = station,
            mediaType = mediaType,
            creationDate = creationDate,
            creationYear = creationYear,
            url = url
        )
    }
}