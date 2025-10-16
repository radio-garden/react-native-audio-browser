package com.audiobrowser

import androidx.media3.common.Metadata
import com.audiobrowser.event.ControllerConnectedEvent
import com.audiobrowser.event.ControllerDisconnectedEvent
import com.audiobrowser.event.PlaybackActiveTrackChangedEvent
import com.audiobrowser.event.PlaybackErrorEvent
import com.audiobrowser.event.PlaybackPlayWhenReadyChangedEvent
import com.audiobrowser.event.PlaybackPlayingStateEvent
import com.audiobrowser.event.PlaybackProgressUpdatedEvent
import com.audiobrowser.event.PlaybackQueueEndedEvent
import com.audiobrowser.event.RemoteJumpBackwardEvent
import com.audiobrowser.event.RemoteJumpForwardEvent
import com.audiobrowser.event.RemoteSeekEvent
import com.audiobrowser.event.RemoteSetRatingEvent
import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.model.PlaybackState
import com.facebook.react.bridge.WritableMap

/** Callbacks for all player events. */
interface AudioPlayerCallbacks {
  // Playback state events
  fun onPlaybackState(state: PlaybackState)

  fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent)

  fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent)

  fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent)

  fun onPlaybackPlayingState(event: PlaybackPlayingStateEvent)

  fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent)

  fun onPlaybackError(event: PlaybackErrorEvent)

  // Metadata events
  fun onMetadataCommonReceived(metadata: WritableMap)

  fun onMetadataTimedReceived(metadata: Metadata)

  fun onPlaybackMetadata(metadata: PlaybackMetadata?)

  // Remote control events
  fun onRemotePlay()

  fun onRemotePause()

  fun onRemoteStop()

  fun onRemoteNext()

  fun onRemotePrevious()

  fun onRemoteJumpForward(event: RemoteJumpForwardEvent)

  fun onRemoteJumpBackward(event: RemoteJumpBackwardEvent)

  fun onRemoteSeek(event: RemoteSeekEvent)

  fun onRemoteSetRating(event: RemoteSetRatingEvent)

  // Android-specific events
  fun onControllerConnected(event: ControllerConnectedEvent)

  fun onControllerDisconnected(event: ControllerDisconnectedEvent)
}
