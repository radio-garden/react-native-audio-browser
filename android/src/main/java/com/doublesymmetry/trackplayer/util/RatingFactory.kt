package com.doublesymmetry.trackplayer.util

import androidx.media3.common.Rating
import androidx.media3.common.HeartRating as Media3HeartRating
import androidx.media3.common.PercentageRating as Media3PercentRating
import androidx.media3.common.StarRating as Media3StarRating
import androidx.media3.common.ThumbRating as Media3ThumbRating
import com.margelo.nitro.audiobrowser.HeartRating
import com.margelo.nitro.audiobrowser.PercentageRating
import com.margelo.nitro.audiobrowser.StarRating
import com.margelo.nitro.audiobrowser.ThumbsRating
import com.margelo.nitro.audiobrowser.Variant_HeartRating_ThumbsRating_StarRating_PercentageRating

object RatingFactory {
    fun convertRatingToVariant(rating: Rating): Variant_HeartRating_ThumbsRating_StarRating_PercentageRating? {
        return when (rating) {
            is Media3HeartRating -> {
                if (rating.isRated) {
                    Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.create(
                        HeartRating(rating.isHeart)
                    )
                } else null
            }
            is Media3ThumbRating -> {
                if (rating.isRated) {
                    Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.create(
                        ThumbsRating(rating.isThumbsUp)
                    )
                } else null
            }
            is Media3StarRating -> {
                if (rating.isRated) {
                    Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.create(
                        StarRating(rating.starRating.toDouble())
                    )
                } else null
            }
            is Media3PercentRating -> {
                if (rating.isRated) {
                    Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.create(
                        PercentageRating(rating.percent.toDouble())
                    )
                } else null
            }
            else -> null
        }
    }
}