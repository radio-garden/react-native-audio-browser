package com.doublesymmetry.trackplayer.model

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import com.doublesymmetry.trackplayer.util.BundleUtils
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.Track as NitroTrack
import com.margelo.nitro.audiobrowser.TrackType

class Track
private constructor(
  // Network/File info
  val url: String?,
  val uri: Uri?,
  val resourceId: Int?,
  val type: MediaType,
  val contentType: String?,
  val userAgent: String?,
  val headers: Map<String, String>?,

  // Metadata
  val title: String?,
  val artist: String?,
  val album: String?,
  val artwork: String?,
  val date: String?,
  val genre: String?,
  val duration: Double?,
  val rating: Rating?,
  val mediaId: String?,

  // Preserve original bridge data to maintain custom fields
  private val originalItem: Bundle,
) {
  fun toMediaItem(): MediaItem {
    val extras =
      Bundle().apply {
        headers?.let { putSerializable("headers", HashMap(it)) }
        userAgent?.let { putString("user-agent", it) }
        resourceId?.let { putInt("resource-id", it) }
        putString("type", type.toString())
        putString("uri", uri?.toString() ?: "")
      }
    // TODO: A playable item can still be browsable and a browsable item can be playable,
    // this logic needs to be revisited:
    var playable = url != null || uri != null
    val mediaMetadata =
      MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setArtworkUri(artwork?.toUri())
        .setExtras(extras)
        .setIsBrowsable(!playable)
        .setIsPlayable(playable)
        .build()

    return MediaItem.Builder()
      .setMediaId(mediaId ?: uri?.toString() ?: "")
      .setUri(uri)
      .setMediaMetadata(mediaMetadata)
      .setTag(this)
      .build()
  }

  fun updateMetadata(
    title: String? = this.title,
    artist: String? = this.artist,
    album: String? = this.album,
    artwork: String? = this.artwork,
    date: String? = this.date,
    genre: String? = this.genre,
    duration: Double? = this.duration,
    rating: Rating? = this.rating,
    mediaId: String? = this.mediaId,
  ): Track {
    // Merge updates into the originalItem to preserve custom fields
    val updatedBundle = Bundle(originalItem)
    title?.let { updatedBundle.putString("title", it) }
    artist?.let { updatedBundle.putString("artist", it) }
    album?.let { updatedBundle.putString("album", it) }
    artwork?.let { updatedBundle.putString("artwork", it) }
    date?.let { updatedBundle.putString("date", it) }
    genre?.let { updatedBundle.putString("genre", it) }
    duration?.let { updatedBundle.putDouble("duration", it) }
    mediaId?.let { updatedBundle.putString("mediaId", it) }
    // Note: Rating is not stored in bundle as it's handled by BundleUtils

    return Track(
      url = url,
      uri = uri,
      resourceId = resourceId,
      type = type,
      contentType = contentType,
      userAgent = userAgent,
      headers = headers,
      title = title,
      artist = artist,
      album = album,
      artwork = artwork,
      date = date,
      genre = genre,
      duration = duration,
      rating = rating,
      mediaId = mediaId,
      originalItem = updatedBundle,
    )
  }

  fun toNitro(): NitroTrack {
    // Convert MediaType to Nitro TrackType
    val trackType = type.toNitro()

    // Get description from original bundle if it exists
    val description = originalItem.getString("description")
    val isLiveStream = if (originalItem.containsKey("isLiveStream")) {
      originalItem.getBoolean("isLiveStream")
    } else null

    return NitroTrack(
      mediaId = mediaId,
      url = url ?: uri?.toString() ?: "",
      type = trackType,
      userAgent = userAgent,
      contentType = contentType,
      pitchAlgorithm = null, // TODO: Convert if needed
      title = title,
      album = album,
      artist = artist,
      duration = duration,
      artwork = artwork,
      description = description,
      genre = genre,
      date = date,
      rating = null, // TODO: Convert rating if needed
      isLiveStream = isLiveStream
    )
  }

  companion object {
    fun fromMediaItem(item: MediaItem): Track {
      return item.localConfiguration!!.tag as Track
    }

    fun fromBridge(context: Context, map: ReadableMap, ratingType: Int): Track {
      // Validate that only expected properties exist (except for 'data' which can have anything)
      validateFromBridge(map)

      // Store original map as Bundle to preserve custom fields
      val originalBundle = readableMapToBundle(map)

      val resourceId = BundleUtils.getRawResourceId(context, map, "url")
      val uri =
        if (resourceId == 0) {
          BundleUtils.getUri(context, map, "url")
        } else {
          Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .path(resourceId.toString())
            .build()
        }

      // Parse headers
      val headers =
        map.getMap("headers")?.let { headerMap ->
          val headerHashMap = HashMap<String, String>()
          val iterator = headerMap.keySetIterator()
          while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            headerHashMap[key] = headerMap.getString(key) ?: ""
          }
          headerHashMap
        }

      // Parse type
      val trackType = map.getString("type") ?: "default"
      var mediaType = MediaType.DEFAULT
      for (t in MediaType.entries) {
        if (t.name.equals(trackType, ignoreCase = true)) {
          mediaType = t
          break
        }
      }
      return Track(
        url =
          if (map.hasKey("url") && map.getType("url") == ReadableType.String) map.getString("url")
          else null,
        uri = uri,
        resourceId = if (resourceId == 0) null else resourceId,
        type = mediaType,
        contentType = map.getString("contentType"),
        userAgent = map.getString("userAgent"),
        headers = headers,
        title = map.getString("title"),
        artist = map.getString("artist"),
        album = map.getString("album"),
        artwork = BundleUtils.getUri(context, map, "artwork")?.toString(),
        date = map.getString("date"),
        genre = map.getString("genre"),
        duration = if (map.hasKey("duration")) map.getDouble("duration") else null,
        rating = BundleUtils.getRating(map, "rating", ratingType),
        mediaId = map.getString("mediaId"),
        originalItem = originalBundle,
      )
    }

    fun fromNitro(nitroTrack: NitroTrack, context: Context): Track {
      // Convert Nitro TrackType to MediaType
      val mediaType = nitroTrack.type?.let { MediaType.fromNitro(it) } ?: MediaType.DEFAULT

      // Create a temporary bundle to use existing URI resolution logic
      val tempBundle = Bundle().apply {
        putString("url", nitroTrack.url)
        nitroTrack.artwork?.let { putString("artwork", it) }
      }
      val tempMap = Arguments.fromBundle(tempBundle)

      // Use existing logic for resource ID and URI resolution
      val resourceId = BundleUtils.getRawResourceId(context, tempMap, "url")
      val uri = if (resourceId == 0) {
        BundleUtils.getUri(context, tempMap, "url")
      } else {
        Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          .path(resourceId.toString())
          .build()
      }

      // Create a basic Bundle to store the original data
      val originalBundle = Bundle().apply {
        putString("url", nitroTrack.url)
        nitroTrack.mediaId?.let { putString("mediaId", it) }
        nitroTrack.type?.let { putString("type", it.name.lowercase()) }
        nitroTrack.userAgent?.let { putString("userAgent", it) }
        nitroTrack.contentType?.let { putString("contentType", it) }
        nitroTrack.title?.let { putString("title", it) }
        nitroTrack.artist?.let { putString("artist", it) }
        nitroTrack.album?.let { putString("album", it) }
        nitroTrack.artwork?.let { putString("artwork", it) }
        nitroTrack.description?.let { putString("description", it) }
        nitroTrack.genre?.let { putString("genre", it) }
        nitroTrack.date?.let { putString("date", it) }
        nitroTrack.duration?.let { putDouble("duration", it) }
        nitroTrack.isLiveStream?.let { putBoolean("isLiveStream", it) }
      }

      return Track(
        url = nitroTrack.url,
        uri = uri,
        resourceId = if (resourceId == 0) null else resourceId,
        type = mediaType,
        contentType = nitroTrack.contentType,
        userAgent = nitroTrack.userAgent,
        headers = null, // Nitro Track doesn't have headers field
        title = nitroTrack.title,
        artist = nitroTrack.artist,
        album = nitroTrack.album,
        artwork = BundleUtils.getUri(context, tempMap, "artwork")?.toString(),
        date = nitroTrack.date,
        genre = nitroTrack.genre,
        duration = nitroTrack.duration,
        rating = null, // TODO: Convert nitroTrack.rating to Rating if needed
        mediaId = nitroTrack.mediaId,
        originalItem = originalBundle,
      )
    }

    private fun readableMapToBundle(map: ReadableMap): Bundle {
      val bundle = Bundle()
      val iterator = map.keySetIterator()
      while (iterator.hasNextKey()) {
        val key = iterator.nextKey()
        when (map.getType(key)) {
          ReadableType.Null -> {}
          ReadableType.Boolean -> bundle.putBoolean(key, map.getBoolean(key))
          ReadableType.Number -> {
            val value = map.getDouble(key)
            if (value == value.toInt().toDouble()) {
              bundle.putInt(key, value.toInt())
            } else {
              bundle.putDouble(key, value)
            }
          }

          ReadableType.String -> bundle.putString(key, map.getString(key))
          ReadableType.Map ->
            map.getMap(key)?.let { bundle.putBundle(key, readableMapToBundle(it)) }

          ReadableType.Array -> {
            // Store array as an ArrayList to preserve it in the bundle
            map.getArray(key)?.let { array ->
              val list = ArrayList<Any?>()
              for (i in 0 until array.size()) {
                when (array.getType(i)) {
                  ReadableType.Null -> list.add(null)
                  ReadableType.Boolean -> list.add(array.getBoolean(i))
                  ReadableType.Number -> list.add(array.getDouble(i))
                  ReadableType.String -> list.add(array.getString(i))
                  ReadableType.Map -> array.getMap(i)?.let { list.add(readableMapToBundle(it)) }
                  ReadableType.Array -> {} // Skip nested arrays for simplicity
                }
              }
              bundle.putSerializable(key, list)
            }
          }
        }
      }
      return bundle
    }

    private val allowedBridgeKeys =
      setOf(
        // From Track interface
        "url",
        "type",
        "userAgent",
        "contentType",
        "pitchAlgorithm",
        "headers",
        "data",
        // From TrackMetadataBase interface
        "title",
        "album",
        "artist",
        "duration",
        "artwork",
        "description",
        "genre",
        "date",
        "rating",
        "isLiveStream",
        "mediaId",
      )

    private fun validateFromBridge(map: ReadableMap) {
      val keys = buildList {
        val iterator = map.keySetIterator()
        while (iterator.hasNextKey()) add(iterator.nextKey())
      }
      val unexpectedKeys = keys - allowedBridgeKeys

      if (unexpectedKeys.isNotEmpty()) {
        throw IllegalArgumentException(
          "Track has unexpected properties: ${unexpectedKeys.joinToString(", ")}. " +
            "Only 'data' field can contain custom properties."
        )
      }
    }
  }
}

/**
 * Factory for creating Track objects with injected context and rating type. Eliminates the need to
 * pass context and ratingType parameters on every Track creation.
 */
class TrackFactory(private val context: Context, private val getRatingType: () -> Int) {
  fun fromBridge(map: ReadableMap): Track {
    return Track.fromBridge(context, map, getRatingType())
  }

  fun tracksFromBridge(tracks: ReadableArray): List<Track> {
    return (0 until tracks.size()).mapNotNull { i ->
      tracks.takeIf { it.getType(i) == ReadableType.Map }?.getMap(i)?.let { fromBridge(it) }
    }
  }
}
