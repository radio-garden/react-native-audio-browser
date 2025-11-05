package com.audiobrowser.model

import com.audiobrowser.option.PlayerCapability
import com.margelo.nitro.audiobrowser.UpdateOptions
import com.margelo.nitro.audiobrowser.AndroidUpdateOptions
import com.margelo.nitro.audiobrowser.RatingType as NitroRatingType
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.NitroUpdateOptions
import com.margelo.nitro.audiobrowser.Variant_Array_Capability__NullSentinel
import com.margelo.nitro.audiobrowser.Variant_Double_NullSentinel

/**
 * Update options for the AudioBrowser that can be changed at runtime. These options control player
 * behavior and capabilities that can be modified during playback.
 */
data class PlayerUpdateOptions(
    // Jump intervals
    var forwardJumpInterval: Double = 15.0,
    var backwardJumpInterval: Double = 15.0,
    var progressUpdateEventInterval: Double? = null,

    // Rating and capabilities
    var capabilities: List<PlayerCapability> =
        listOf(
            PlayerCapability.PLAY,
            PlayerCapability.PAUSE,
            PlayerCapability.SKIP_TO_NEXT,
            PlayerCapability.SKIP_TO_PREVIOUS,
            PlayerCapability.SEEK_TO,
        ),
    var notificationCapabilities: List<PlayerCapability>? = null,

    // Android-specific runtime options (all under android.* in JS)
    var ratingType: NitroRatingType? = null,
    var appKilledPlaybackBehavior: AppKilledPlaybackBehavior = AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION,
    var skipSilence: Boolean = false,
    var shuffle: Boolean = false,
) {
    fun updateFromBridge(options: NitroUpdateOptions) {
        options.forwardJumpInterval?.let { forwardJumpInterval = it }
        options.backwardJumpInterval?.let { backwardJumpInterval = it }
        options.progressUpdateEventInterval?.let { variant ->
            progressUpdateEventInterval = when (variant) {
                is Variant_Double_NullSentinel.First -> variant.value
                is Variant_Double_NullSentinel.Second -> null
            }
        }

        options.capabilities?.let { nitroCaps ->
            capabilities = nitroCaps.map { PlayerCapability.fromNitro(it) }
        }

        // Update Android-specific options
        options.android?.let { androidOptions ->
            // Convert rating type
            androidOptions.ratingType?.let {
                ratingType = it
            }

            androidOptions.appKilledPlaybackBehavior?.let {
                appKilledPlaybackBehavior = it
            }

            // Update boolean options
            androidOptions.skipSilence?.let {
                skipSilence = it
            }

            androidOptions.shuffle?.let {
                shuffle = it
            }

            androidOptions.notificationCapabilities?.let { variant ->
                notificationCapabilities = when (variant) {
                    is Variant_Array_Capability__NullSentinel.First -> variant.value.map {
                        PlayerCapability.fromNitro(
                            it
                        )
                    }

                    is Variant_Array_Capability__NullSentinel.Second -> null
                }
            }
        }
    }

    fun toNitro(): UpdateOptions {
        // Convert capabilities
        val nitroCapabilities = capabilities.map { it.toNitro() }.toTypedArray()

        // Convert notification capabilities
        val nitroNotificationCapabilities =
            notificationCapabilities?.map { it.toNitro() }?.toTypedArray()

        // Create Android options
        val androidOptions = AndroidUpdateOptions(
            appKilledPlaybackBehavior = appKilledPlaybackBehavior,
            skipSilence = skipSilence,
            shuffle = shuffle,
            ratingType = ratingType,
            notificationCapabilities = nitroNotificationCapabilities
        )

        return UpdateOptions(
            android = androidOptions,
            ios = null, // iOS options not handled in this class
            forwardJumpInterval = forwardJumpInterval,
            backwardJumpInterval = backwardJumpInterval,
            progressUpdateEventInterval = progressUpdateEventInterval,
            capabilities = nitroCapabilities
        )
    }

}
