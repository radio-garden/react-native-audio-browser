package com.audiobrowser.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.source.ForwardingTimeline

/**
 * A session listener hearing the engine's events with [view]'s state and playing, and never its
 * errors, which [InterceptingPlayer.getPlayerError] hides: the session copies an event's own
 * arguments, so the view has to be in them. A state, playing, timeline or position it was already
 * told is not told again: the session pushes each one to legacy controllers whether or not it
 * changed. Every other event passes as it is.
 */
internal class SessionViewListener(
  private val target: MediaPlayer.Listener,
  private val view: MediaPlayer,
) : MediaPlayer.Listener {
  private var toldState = view.playbackState
  private var toldPlaying = view.isPlaying
  private var toldTimeline = view.currentTimeline

  /**
   * Tells the session the view's state and playing where they moved since it was last told: the
   * view can move with no engine event, as when the retry loop lets go of an idle engine.
   */
  fun refresh() {
    onPlaybackStateChanged(view.playbackState)
    onIsPlayingChanged(view.isPlaying)
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (view.playbackState == toldState) return
    toldState = view.playbackState
    target.onPlaybackStateChanged(toldState)
  }

  @Deprecated("Deprecated in Java")
  override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
    @Suppress("DEPRECATION") target.onPlayerStateChanged(view.playWhenReady, view.playbackState)

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (view.isPlaying == toldPlaying) return
    toldPlaying = view.isPlaying
    target.onIsPlayingChanged(toldPlaying)
  }

  override fun onPlayerError(error: PlaybackException) = Unit

  override fun onPlayerErrorChanged(error: PlaybackException?) = Unit

  override fun onEvents(player: MediaPlayer, events: MediaPlayer.Events) =
    target.onEvents(player, events)

  override fun onTimelineChanged(timeline: Timeline, reason: Int) {
    if (IgnoringPlaceholders(timeline) == IgnoringPlaceholders(toldTimeline)) return
    toldTimeline = timeline
    target.onTimelineChanged(timeline, reason)
  }

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
    target.onMediaItemTransition(mediaItem, reason)

  override fun onTracksChanged(tracks: Tracks) = target.onTracksChanged(tracks)

  override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
    target.onMediaMetadataChanged(mediaMetadata)

  override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
    target.onPlaylistMetadataChanged(mediaMetadata)

  override fun onIsLoadingChanged(isLoading: Boolean) = target.onIsLoadingChanged(isLoading)

  @Deprecated("Deprecated in Java")
  override fun onLoadingChanged(isLoading: Boolean) =
    @Suppress("DEPRECATION") target.onLoadingChanged(isLoading)

  override fun onAvailableCommandsChanged(availableCommands: MediaPlayer.Commands) =
    target.onAvailableCommandsChanged(availableCommands)

  override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
    target.onTrackSelectionParametersChanged(parameters)

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
    target.onPlayWhenReadyChanged(playWhenReady, reason)

  override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
    target.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

  override fun onRepeatModeChanged(repeatMode: Int) = target.onRepeatModeChanged(repeatMode)

  override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
    target.onShuffleModeEnabledChanged(shuffleModeEnabled)

  @Deprecated("Deprecated in Java")
  override fun onPositionDiscontinuity(reason: Int) =
    @Suppress("DEPRECATION") target.onPositionDiscontinuity(reason)

  override fun onPositionDiscontinuity(
    oldPosition: MediaPlayer.PositionInfo,
    newPosition: MediaPlayer.PositionInfo,
    reason: Int,
  ) {
    if (oldPosition == newPosition) return
    target.onPositionDiscontinuity(oldPosition, newPosition, reason)
  }

  override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
    target.onPlaybackParametersChanged(playbackParameters)

  override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
    target.onSeekBackIncrementChanged(seekBackIncrementMs)

  override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
    target.onSeekForwardIncrementChanged(seekForwardIncrementMs)

  override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
    target.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

  override fun onAudioSessionIdChanged(audioSessionId: Int) =
    target.onAudioSessionIdChanged(audioSessionId)

  override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
    target.onAudioAttributesChanged(audioAttributes)

  override fun onVolumeChanged(volume: Float) = target.onVolumeChanged(volume)

  override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
    target.onSkipSilenceEnabledChanged(skipSilenceEnabled)

  override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = target.onDeviceInfoChanged(deviceInfo)

  override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
    target.onDeviceVolumeChanged(volume, muted)

  override fun onVideoSizeChanged(videoSize: VideoSize) = target.onVideoSizeChanged(videoSize)

  override fun onSurfaceSizeChanged(width: Int, height: Int) =
    target.onSurfaceSizeChanged(width, height)

  override fun onRenderedFirstFrame() = target.onRenderedFirstFrame()

  @Deprecated("Deprecated in Java")
  override fun onCues(cues: List<Cue>) = @Suppress("DEPRECATION") target.onCues(cues)

  override fun onCues(cueGroup: CueGroup) = target.onCues(cueGroup)

  override fun onMetadata(metadata: Metadata) = target.onMetadata(metadata)
}

/**
 * Reads every period as real: a re-prepare marks the same period a placeholder, then real again.
 */
private class IgnoringPlaceholders(timeline: Timeline) : ForwardingTimeline(timeline) {
  override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
    super.getPeriod(periodIndex, period, setIds).apply { isPlaceholder = false }
}
