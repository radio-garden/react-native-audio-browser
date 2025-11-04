package com.doublesymmetry.trackplayer.util

import android.media.browse.MediaBrowser
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.margelo.nitro.audiobrowser.Track

object TrackFactory {

    fun media3ToBridge(mediaItems: List<MediaItem>): Array<Track> {
        return mediaItems.map { media3ToBridge(it) }.toTypedArray()
    }

    fun media3ToBridge(mediaItem: MediaItem): Track {
        return mediaItem.localConfiguration!!.tag as Track
    }

    fun bridgeToMedia3(tracks: Array<Track>): List<MediaItem> {
        return tracks.map { bridgeToMedia3(it) }
    }
    fun bridgeToMedia3(track: Track): MediaItem {
        var playable = track.url != null
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
                track.mediaId ?: track.url
                ?: throw IllegalArgumentException("Track must have either mediaId or url")
            )
            .setUri(track.url)
            .setMediaMetadata(mediaMetadata)
            .setTag(track)
            .build()

    }
}