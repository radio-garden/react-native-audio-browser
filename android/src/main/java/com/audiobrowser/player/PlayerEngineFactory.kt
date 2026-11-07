package com.audiobrowser.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.AndroidAudioContentTypeFactory
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.Track
import java.io.IOException

// media3's no-progress stuck-buffering check (STUCK_BUFFERING_NO_PROGRESS); its 600s default
// would be ten minutes of dead air on a live stream. The not-loading check is a fixed 4s and not
// configurable.
private const val STUCK_BUFFERING_DETECTION_TIMEOUT_MS = 60_000

internal class PlayerEngine(
  val loadControl: DynamicLoadControl,
  val mediaFactory: MediaFactory,
  val exoPlayer: ExoPlayer,
)

/**
 * A null `rebufferBuffer` means automatic: post-rebuffer playback starts at `playBuffer` and
 * AutomaticBufferManager adjusts it from there.
 */
internal fun bufferConfig(options: PlayerSetupOptions): BufferConfig =
  BufferConfig(
    minBufferMs = options.minBuffer.toInt(),
    maxBufferMs = options.maxBuffer.toInt(),
    bufferForPlaybackMs = options.playBuffer.toInt(),
    bufferForPlaybackAfterRebufferMs = (options.rebufferBuffer ?: options.playBuffer).toInt(),
    backBufferMs = options.backBuffer.toInt(),
  )

/**
 * Constructs the load control, media factory and ExoPlayer for one `setup()` generation, and
 * nothing else: tearing down the previous engine and wiring listeners stay with [Player.setup]. The
 * lambdas run on ExoPlayer's background threads (playback and loader), never main, so they must
 * read thread-safe state.
 */
internal fun buildPlayerEngine(
  context: Context,
  options: PlayerSetupOptions,
  cache: SimpleCache?,
  shouldRetry: () -> Boolean,
  isOnline: () -> Boolean,
  hasPlayed: () -> Boolean,
  onRetryPending: (exception: IOException) -> Unit,
  resolveMediaConfig: (url: String, track: Track) -> MediaRequestConfig?,
): PlayerEngine {
  val renderer = DefaultRenderersFactory(context)
  renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

  val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

  val loadControl = DynamicLoadControl(initialConfig = bufferConfig(options))

  val mediaFactory =
    MediaFactory(
      context,
      cache,
      options.retryPolicy,
      shouldRetry = shouldRetry,
      isOnline = isOnline,
      hasPlayed = hasPlayed,
      onRetryPending = onRetryPending,
      transferListener = bandwidthMeter,
      getRequestConfig = resolveMediaConfig,
    )

  val exoPlayer =
    ExoPlayer.Builder(context)
      .setRenderersFactory(renderer)
      .setBandwidthMeter(bandwidthMeter)
      .setHandleAudioBecomingNoisy(options.handleAudioBecomingNoisy)
      .setMediaSourceFactory(mediaFactory)
      .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_DETECTION_TIMEOUT_MS)
      .setWakeMode(
        when (options.wakeMode) {
          AndroidPlayerWakeMode.NONE -> C.WAKE_MODE_NONE
          AndroidPlayerWakeMode.LOCAL -> C.WAKE_MODE_LOCAL
          AndroidPlayerWakeMode.NETWORK -> C.WAKE_MODE_NETWORK
        }
      )
      .setLoadControl(loadControl)
      .setName("AudioBrowser")
      .build()

  exoPlayer.setAudioAttributes(
    AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(AndroidAudioContentTypeFactory.toMedia3(options.audioContentType))
      .build(),
    true, // handle audio focus
  )

  options.audioOffload?.let {
    val audioOffloadPreferences =
      TrackSelectionParameters.AudioOffloadPreferences.Builder()
        .setAudioOffloadMode(
          TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        )
        .setIsGaplessSupportRequired(it.gaplessSupportRequired)
        .setIsSpeedChangeSupportRequired(it.rateChangeSupportRequired)
        .build()
    // Assigning the field directly throws IllegalAccessError on ART, and mutating the getter's
    // snapshot never reaches the selector: go through the builder.
    exoPlayer.trackSelectionParameters =
      exoPlayer.trackSelectionParameters
        .buildUpon()
        .setAudioOffloadPreferences(audioOffloadPreferences)
        .build()
  }

  return PlayerEngine(loadControl, mediaFactory, exoPlayer)
}
