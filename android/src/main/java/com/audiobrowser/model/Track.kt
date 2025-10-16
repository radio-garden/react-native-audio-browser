package com.audiobrowser.model

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.HeartRating
import androidx.media3.common.PercentageRating
import androidx.media3.common.Rating
import androidx.media3.common.StarRating
import androidx.media3.common.ThumbRating
import androidx.media3.session.legacy.RatingCompat
import androidx.media3.common.util.UnstableApi
import com.audiobrowser.util.BundleUtils
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap

@OptIn(UnstableApi::class)
class Track
constructor(
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
  val rating: RatingCompat?,
  val mediaId: String?,
  val isPlayable: Boolean = true,
  val isBrowsable: Boolean = false,

  // Preserve original bridge data to maintain custom fields
  private val originalItem: Bundle?,
) {
  fun toMediaItem(): MediaItem {
    // Create minimal extras bundle with only technical properties that can't be stored in standard fields
    val minimalExtras = createMinimalExtras()

    val mediaMetadata =
      MediaMetadata.Builder()
        // Standard metadata fields (preserved by Android Auto)
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setGenre(genre)
        .setArtworkUri(artwork?.toUri())
        .setIsBrowsable(isBrowsable)
        .setIsPlayable(isPlayable)
        // Convert duration from seconds to milliseconds
        .apply { duration?.let { setDurationMs((it * 1000).toLong()) } }
        // Convert rating from RatingCompat to Rating
        .apply { rating?.let { setUserRating(convertRatingCompatToRating(it)) } }
        // Parse date and extract recording year
        .apply { date?.let { parseRecordingYear(it)?.let { year -> setRecordingYear(year) } } }
        // Only include extras if we have technical properties that need them
        .apply { minimalExtras?.let { setExtras(it) } }
        .build()

    return MediaItem.Builder()
      .setMediaId(mediaId ?: uri?.toString() ?: "")
      .setUri(uri)
      .setMediaMetadata(mediaMetadata)
      .build()
  }

  fun toBridge(): WritableMap {
    // Return the original item to preserve custom fields
    return Arguments.fromBundle(originalItem ?: Bundle())
  }

  private fun createMinimalExtras(): Bundle? {
    // Only create extras bundle if we have non-standard technical properties
    val hasNonStandardProperties = type != MediaType.DEFAULT ||
                                   contentType != null ||
                                   userAgent != null ||
                                   headers?.isNotEmpty() == true ||
                                   resourceId != null

    return if (hasNonStandardProperties) {
      Bundle().apply {
        // Only store essential technical properties that can't be mapped to standard MediaMetadata fields
        // Note: URL is NOT stored - it can be reconstructed from MediaItem.uri
        if (type != MediaType.DEFAULT) putString("type", type.toString())
        contentType?.let { putString("contentType", it) }
        userAgent?.let { putString("userAgent", it) }
        headers?.takeIf { it.isNotEmpty() }?.let { putSerializable("headers", HashMap(it)) }
        resourceId?.let { putInt("resource-id", it) }
      }
    } else {
      // No extras needed - all data is in standard fields!
      null
    }
  }

  private fun convertRatingCompatToRating(ratingCompat: RatingCompat): Rating? {
    return when (ratingCompat.ratingStyle) {
      RatingCompat.RATING_HEART -> HeartRating(ratingCompat.hasHeart())
      RatingCompat.RATING_THUMB_UP_DOWN -> ThumbRating(ratingCompat.isThumbUp)
      RatingCompat.RATING_PERCENTAGE -> PercentageRating(ratingCompat.percentRating)
      RatingCompat.RATING_3_STARS -> StarRating(3, ratingCompat.starRating)
      RatingCompat.RATING_4_STARS -> StarRating(4, ratingCompat.starRating)
      RatingCompat.RATING_5_STARS -> StarRating(5, ratingCompat.starRating)
      else -> null
    }
  }

  private fun parseRecordingYear(dateString: String): Int? {
    return try {
      // Try to parse common date formats and extract year
      when {
        // ISO date format: 2023-12-25
        dateString.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> dateString.substring(0, 4).toInt()
        // Year only: 2023
        dateString.matches(Regex("\\d{4}")) -> dateString.toInt()
        // Other formats - try to extract first 4-digit year
        else -> Regex("\\d{4}").find(dateString)?.value?.toInt()
      }
    } catch (e: Exception) {
      null
    }
  }

  fun toBundle(): Bundle {
    // Return the original item if available to preserve custom fields
    return originalItem ?: Bundle().apply {
      url?.let { putString("url", it) }
      putString("type", type.toString())
      contentType?.let { putString("contentType", it) }
      userAgent?.let { putString("userAgent", it) }
      headers?.let { putSerializable("headers", HashMap(it)) }
      title?.let { putString("title", it) }
      artist?.let { putString("artist", it) }
      album?.let { putString("album", it) }
      artwork?.let { putString("artwork", it) }
      date?.let { putString("date", it) }
      genre?.let { putString("genre", it) }
      duration?.let { putDouble("duration", it) }
      mediaId?.let { putString("mediaId", it) }
      putBoolean("isPlayable", isPlayable)
      putBoolean("isBrowsable", isBrowsable)
      resourceId?.let { putInt("resource-id", it) }
      uri?.let { putString("uri", it.toString()) }
    }
  }

  fun updateMetadata(
    title: String? = this.title,
    artist: String? = this.artist,
    album: String? = this.album,
    artwork: String? = this.artwork,
    date: String? = this.date,
    genre: String? = this.genre,
    duration: Double? = this.duration,
    rating: RatingCompat? = this.rating,
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

  companion object {
    fun fromMediaItem(item: MediaItem, context: Context, ratingType: Int): Track {
      val metadata = item.mediaMetadata
      val extras = metadata.extras // May be null if stripped by Android Auto

      // Extract standard metadata fields (preserved by Android Auto)
      val title = metadata.title?.toString()
      val artist = metadata.artist?.toString()
      val album = metadata.albumTitle?.toString()
      val genre = metadata.genre?.toString()
      val artwork = metadata.artworkUri?.toString()
      val isPlayable = metadata.isPlayable ?: true
      val isBrowsable = metadata.isBrowsable ?: false

      // Convert duration from milliseconds to seconds
      val duration = metadata.durationMs?.let { if (it > 0) it / 1000.0 else null }

      // Convert rating from Rating to RatingCompat
      val rating = metadata.userRating?.let { convertToRatingCompat(it) }

      // Reconstruct date string from recording year (best effort)
      val date = metadata.recordingYear?.let { it.toString() }

      // Get technical properties from extras (with fallbacks)
      val typeString = extras?.getString("type") ?: "default"
      val contentType = extras?.getString("contentType")
      val userAgent = extras?.getString("userAgent")
      val resourceId = extras?.getInt("resource-id")?.takeIf { it != 0 }

      // Reconstruct URL from MediaItem URI (since we no longer store it in extras)
      val url = item.localConfiguration?.uri?.toString()

      // Parse headers from extras
      @Suppress("DEPRECATION")
      val headers = extras?.getSerializable("headers")?.let { headerMap ->
        val headerHashMap = HashMap<String, String>()
        if (headerMap is HashMap<*, *>) {
          for ((key, value) in headerMap) {
            if (key is String && value is String) {
              headerHashMap[key] = value
            }
          }
        }
        headerHashMap
      }

      // Parse MediaType
      var mediaType = MediaType.DEFAULT
      for (t in MediaType.entries) {
        if (t.name.equals(typeString, ignoreCase = true)) {
          mediaType = t
          break
        }
      }

      // Handle URI - prefer MediaItem.uri, fallback to resource URI or URL
      val uri = item.localConfiguration?.uri ?: when {
        resourceId != null -> Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          .path(resourceId.toString())
          .build()
        url != null -> url.toUri()
        else -> null
      }

      // Create a combined bundle for originalItem (merge standard fields with extras)
      val originalBundle = Bundle().apply {
        // Store standard metadata fields for bridge compatibility
        title?.let { putString("title", it) }
        artist?.let { putString("artist", it) }
        album?.let { putString("album", it) }
        genre?.let { putString("genre", it) }
        artwork?.let { putString("artwork", it) }
        duration?.let { putDouble("duration", it) }
        date?.let { putString("date", it) }
        putBoolean("isPlayable", isPlayable)
        putBoolean("isBrowsable", isBrowsable)

        // Add technical properties (from extras and reconstructed)
        url?.let { putString("url", it) } // Reconstructed from URI
        putString("type", typeString)
        contentType?.let { putString("contentType", it) }
        userAgent?.let { putString("userAgent", it) }
        resourceId?.let { putInt("resource-id", it) }
        headers?.let { putSerializable("headers", HashMap(it)) }

        // Store mediaId and uri
        item.mediaId?.let { putString("mediaId", it) }
        uri?.let { putString("uri", it.toString()) }
      }

      return Track(
        url = url,
        uri = uri,
        resourceId = resourceId,
        type = mediaType,
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
        mediaId = item.mediaId,
        isPlayable = isPlayable,
        isBrowsable = isBrowsable,
        originalItem = originalBundle,
      )
    }

    fun fromBundle(context: Context, bundle: Bundle, ratingType: Int): Track {
      // Handle resource ID
      val urlString = bundle.getString("url")
      val resourceId = if (urlString != null) {
        val resourceName = urlString.substringAfterLast("/", "")
        if (resourceName.isNotEmpty()) {
          try {
            context.resources.getIdentifier(resourceName, "raw", context.packageName)
          } catch (e: Exception) {
            0
          }
        } else 0
      } else 0

      val uri = if (resourceId != 0) {
        Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          .path(resourceId.toString())
          .build()
      } else {
        urlString?.toUri()
      }

      // Parse headers
      @Suppress("DEPRECATION")
      val headers =
        bundle.getSerializable("headers")?.let { headerMap ->
          val headerHashMap = HashMap<String, String>()
          if (headerMap is HashMap<*, *>) {
            for ((key, value) in headerMap) {
              if (key is String && value is String) {
                headerHashMap[key] = value
              }
            }
          }
          headerHashMap
        }

      // Parse type
      val trackType = bundle.getString("type") ?: "default"
      var mediaType = MediaType.DEFAULT
      for (t in MediaType.entries) {
        if (t.name.equals(trackType, ignoreCase = true)) {
          mediaType = t
          break
        }
      }

      // Parse artwork URI
      val artworkString = bundle.getString("artwork")
      val artworkUri = if (artworkString != null) {
        try {
          artworkString.toUri()
        } catch (e: Exception) {
          null
        }
      } else null

      // Parse rating - ratings are not currently stored in bundles, so set to null
      // This matches the toBundle() implementation which has the comment:
      // "Note: Rating is not stored in bundle as it's handled by BundleUtils"
      val rating: RatingCompat? = null

      return Track(
        url = bundle.getString("url"),
        uri = uri,
        resourceId = if (resourceId == 0) null else resourceId,
        type = mediaType,
        contentType = bundle.getString("contentType"),
        userAgent = bundle.getString("userAgent"),
        headers = headers,
        title = bundle.getString("title"),
        artist = bundle.getString("artist"),
        album = bundle.getString("album"),
        artwork = artworkUri?.toString(),
        date = bundle.getString("date"),
        genre = bundle.getString("genre"),
        duration = if (bundle.containsKey("duration")) bundle.getDouble("duration") else null,
        rating = rating,
        mediaId = bundle.getString("mediaId"),
        isPlayable = bundle.getBoolean("isPlayable", true),
        isBrowsable = bundle.getBoolean("isBrowsable", false),
        originalItem = bundle,
      )
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
        url = if (map.hasKey("url") && map.getType("url") == ReadableType.String) map.getString("url") else null,
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
        rating = convertToRatingCompat(BundleUtils.getRating(map, "rating", ratingType)),
        mediaId = map.getString("mediaId"),
        originalItem = originalBundle,
      )
    }

    private fun convertToRatingCompat(rating: Rating?): RatingCompat? {
      return when (rating) {
        is HeartRating -> RatingCompat.newHeartRating(rating.isHeart)
        is ThumbRating -> RatingCompat.newThumbRating(rating.isThumbsUp)
        is PercentageRating -> RatingCompat.newPercentageRating(rating.percent)
        is StarRating -> RatingCompat.newStarRating(rating.maxStars, rating.starRating)
        else -> null
      }
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
