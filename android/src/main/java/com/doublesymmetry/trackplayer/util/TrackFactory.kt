package com.doublesymmetry.trackplayer.util

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.margelo.nitro.audiobrowser.Track

object TrackFactory {

    fun fromMedia3(mediaItems: List<MediaItem>): Array<Track> {
        return mediaItems.map { fromMedia3(it) }.toTypedArray()
    }

    fun fromMedia3(mediaItem: MediaItem): Track {
        return mediaItem.localConfiguration!!.tag as Track
    }

    fun toMedia3(tracks: Array<Track>): List<MediaItem> {
        return tracks.map { toMedia3(it) }
    }
    fun toMedia3(track: Track): MediaItem {
        // TODO: this needs to be reconsidered:
        val playable = track.url != null
        val mediaMetadata =
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setDescription(track.description)
                .setGenre(track.genre)
                .setArtworkUri(track.artwork?.toUri())
                .setIsBrowsable(!playable)
                .setIsPlayable(playable)
                .build()

        return MediaItem.Builder()
            .setMediaId(
                track.url ?: track.src
            )
            .setUri(track.src)
            .setMediaMetadata(mediaMetadata)
            .setTag(track)
            .build()

    }
}