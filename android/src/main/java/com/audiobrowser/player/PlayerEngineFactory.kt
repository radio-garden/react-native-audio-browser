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

// media3's configurable stuck-buffering (type-1, STUCK_BUFFERING_NO_PROGRESS) timeout. Fires only
// on zero loading progress; 60s of silent non-progress is already a bad live-radio experience,
// while the media3 default of 600s would mean 10 minutes of dead air. (Type-0
// STUCK_BUFFERING_NOT_LOADING is a separate, uncontrollable fixed-4s check.)
private const val STUCK_BUFFERING_DETECTION_TIMEOUT_MS = 60_000

/** The engine pieces [buildPlayerEngine] constructs together for one setup() generation. */
internal class PlayerEngine(
  val loadControl: DynamicLoadControl,
  val mediaFactory: MediaFactory,
  val exoPlayer: ExoPlayer,
)

/**
 * The buffer configuration described by the setup options. When `rebufferBuffer` is unset
 * (automatic), post-rebuffer playback starts at `playBuffer` and AutomaticBufferManager adjusts it;
 * when set, that fixed value is used. Pure — pinned by PlayerEngineFactoryTest.
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
 * Constructs the playback engine for one `setup()` generation: load control, media factory, and the
 * ExoPlayer they plug into — pure construction, no lifecycle. Tearing down the previous generation,
 * wiring listeners/session, and everything stateful stays in [Player.setup], which provides its
 * mutable context through the lambdas.
 */
internal fun buildPlayerEngine(
  context: Context,
  options: PlayerSetupOptions,
  cache: SimpleCache?,
  shouldRetry: () -> Boolean,
  isOnline: () -> Boolean,
  onRetryPending: (isNetworkError: Boolean) -> Unit,
  resolveMediaConfig: (url: String) -> MediaRequestConfig?,
): PlayerEngine {
  val renderer = DefaultRenderersFactory(context)
  renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

  // Bandwidth meter for adaptive bitrate selection in HLS/DASH
  val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

  val loadControl = DynamicLoadControl(initialConfig = bufferConfig(options))

  // shouldRetry checks playWhenReady to avoid retrying when paused (e.g., another app took audio
  // focus) — via a thread-safe cache since this runs on ExoPlayer's playback thread. isOnline and
  // onRetryPending enable network-aware retry acceleration.
  val mediaFactory =
    MediaFactory(
      context,
      cache,
      options.retryPolicy,
      shouldRetry = shouldRetry,
      isOnline = isOnline,
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
    // Assigning the public final field compiles to a putfield that throws
    // IllegalAccessError on ART, and mutating the getter's snapshot wouldn't
    // reach the selector anyway — go through the builder.
    exoPlayer.trackSelectionParameters =
      exoPlayer.trackSelectionParameters
        .buildUpon()
        .setAudioOffloadPreferences(audioOffloadPreferences)
        .build()
  }

  return PlayerEngine(loadControl, mediaFactory, exoPlayer)
}
