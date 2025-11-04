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
    fun media3ToBridge(rating: Rating): Variant_HeartRating_ThumbsRating_StarRating_PercentageRating? {
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

    fun bridgeToMedia3(rating: Variant_HeartRating_ThumbsRating_StarRating_PercentageRating): Rating? {
        return when (rating) {
            is Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.First -> Media3HeartRating(
                rating.value.hasHeart
            )

            is Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.Second -> Media3ThumbRating(
                rating.value.isThumbsUp
            )

            is Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.Third -> Media3StarRating(
                5,
                rating.value.stars.toFloat()
            )

            is Variant_HeartRating_ThumbsRating_StarRating_PercentageRating.Fourth -> Media3PercentRating(
                rating.value.percentage.toFloat()
            )
        }
    }

}