package com.audiobrowser.util

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.SearchMode
import com.margelo.nitro.audiobrowser.SearchParams

object SearchParamsFactory {
  /**
   * The search a play-from-search request asks for, from its query and the extras of
   * https://developer.android.com/guide/components/intents-common#PlaySearch. The intent and the
   * media session's legacy `playFromSearch` carry the same extras.
   */
  fun fromPlayFromSearch(query: String, extras: Bundle?): SearchParams {
    val mediaFocus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)

    // The focus picks a vertical. Genre, artist and album focuses are filters, carried by the
    // extras below, so they yield no mode. Android has no station or podcast focus: those
    // verticals are iOS-only.
    val mode =
      when (mediaFocus) {
        "vnd.android.cursor.item/*" -> if (query.isEmpty()) SearchMode.ANY else null
        "vnd.android.cursor.item/audio" -> SearchMode.SONG
        MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> SearchMode.PLAYLIST
        else -> null
      }

    return SearchParams(
      mode = mode,
      query = query,
      genre = extras?.getString("android.intent.extra.genre"),
      artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
      album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
      title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
      playlist = extras?.getString("android.intent.extra.playlist"),
      reference = MediaReference.UNKNOWN,
    )
  }

  /**
   * The search [mediaItem] asks to play, or null when it names an item. Media3 gives the legacy
   * `playFromSearch` and `prepareFromSearch` as one item with the query and no media id.
   */
  fun fromPlayFromSearch(mediaItem: MediaItem): SearchParams? {
    if (mediaItem.mediaId != MediaItem.DEFAULT_MEDIA_ID) return null
    val request = mediaItem.requestMetadata
    return request.searchQuery?.let { fromPlayFromSearch(it, request.extras) }
  }
}
