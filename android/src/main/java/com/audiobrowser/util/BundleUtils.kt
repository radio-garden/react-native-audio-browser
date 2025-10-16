package com.audiobrowser.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.support.v4.media.RatingCompat
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.PercentageRating
import androidx.media3.common.Rating
import androidx.media3.common.StarRating
import androidx.media3.common.ThumbRating
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper

/**
 * Utility class for converting between React Native bridge data types and Android native types.
 *
 * This class provides a set of helper functions to safely extract and convert data from React
 * Native's ReadableMap and WritableMap objects to Android-specific types like Uri, resource IDs,
 * ratings, and drawable resources.
 */
object BundleUtils {
  fun getUri(context: Context, data: ReadableMap?, key: String?): Uri? {
    if (data == null || key == null || !data.hasKey(key)) return null
    val obj = data.getDynamic(key)
    if (obj.type == ReadableType.String) {
      // Remote or Local Uri
      val uri = obj.asString()
      if (uri?.trim { it <= ' ' }.isNullOrEmpty())
        throw RuntimeException("$key: The URL cannot be empty")
      return uri.toUri()
    } else if (obj.type == ReadableType.Map) {
      // require/import
      val objMap = obj.asMap()
      val uri = objMap?.getString("uri")
      if (uri == null) return null
      val id = ResourceDrawableIdHelper.getResourceDrawableId(context, uri)
      return if (id > 0) {
        // In production, we can obtain the resource uri
        val res = context.resources
        Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          .authority(res.getResourcePackageName(id))
          .appendPath(res.getResourceTypeName(id))
          .appendPath(res.getResourceEntryName(id))
          .build()
      } else {
        // During development, the resources might come directly from the metro server
        uri.toUri()
      }
    }
    return null
  }

  fun getRawResourceId(context: Context, data: ReadableMap, key: String?): Int {
    if (key == null || !data.hasKey(key) || data.getType(key) != ReadableType.Map) return 0
    val obj = data.getMap(key) ?: return 0
    var name = obj.getString("uri")
    if (name.isNullOrEmpty()) return 0
    name = name.lowercase().replace("-", "_")
    return try {
      name.toInt()
    } catch (ex: NumberFormatException) {
      context.resources.getIdentifier(name, "raw", context.packageName)
    }
  }

  fun getRating(data: ReadableMap, key: String?, ratingType: Int): Rating? {
    if (key == null || !data.hasKey(key)) return null
    return when (ratingType) {
      RatingCompat.RATING_HEART -> HeartRating(data.getBoolean(key))
      RatingCompat.RATING_THUMB_UP_DOWN -> ThumbRating(data.getBoolean(key))
      RatingCompat.RATING_PERCENTAGE -> PercentageRating(data.getDouble(key).toFloat())
      RatingCompat.RATING_3_STARS,
      RatingCompat.RATING_4_STARS,
      RatingCompat.RATING_5_STARS -> StarRating(ratingType, data.getDouble(key).toFloat())

      else -> null
    }
  }

  fun setRating(data: WritableMap, key: String?, rating: Rating) {
    if (!rating.isRated || key == null) return
    when (rating) {
      is HeartRating -> data.putBoolean(key, rating.isHeart)
      is ThumbRating -> data.putBoolean(key, rating.isThumbsUp)
      is PercentageRating -> data.putDouble(key, rating.percent.toDouble())
      is StarRating -> data.putDouble(key, rating.starRating.toDouble())
    }
  }
}
