package com.doublesymmetry.trackplayer

import com.doublesymmetry.trackplayer.model.PlaybackMetadata
import com.doublesymmetry.trackplayer.model.PlayerUpdateOptions
import com.doublesymmetry.trackplayer.model.TimedMetadata
import com.margelo.nitro.audiobrowser.AudioMetadata
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import com.margelo.nitro.audiobrowser.RepeatMode

/** Callbacks for all player events. */
interface TrackPlayerCallbacks {
  // Playback state events
  fun onPlaybackChanged(playback: Playback)

  fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent)

  fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent)

  fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent)

  fun onPlaybackPlayingState(event: PlayingState)

  fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent)

  fun onPlaybackRepeatModeChanged(event: RepeatMode)

  fun onPlaybackError(error: PlaybackError?)

  // Metadata events
  fun onMetadataCommonReceived(metadata: AudioMetadata)

  fun onMetadataTimedReceived(metadata: TimedMetadata)

  fun onPlaybackMetadata(metadata: PlaybackMetadata)

  // Remote control events
  fun handleRemotePlay(): Boolean

  fun handleRemotePause(): Boolean

  fun handleRemoteStop(): Boolean

  fun handleRemoteNext(): Boolean

  fun handleRemotePrevious(): Boolean

  fun handleRemoteJumpForward(event: RemoteJumpForwardEvent): Boolean

  fun handleRemoteJumpBackward(event: RemoteJumpBackwardEvent): Boolean

  fun handleRemoteSeek(event: RemoteSeekEvent): Boolean

  fun handleRemoteSetRating(event: RemoteSetRatingEvent): Boolean

  // Configuration events
  fun onOptionsChanged(options: PlayerUpdateOptions)

  // Media browser events
  fun onGetChildrenRequest(requestId: String, parentId: String, page: Int, pageSize: Int)

  fun onGetItemRequest(requestId: String, mediaId: String)

  fun onSearchRequest(requestId: String, query: String, extras: Map<String, Any>?)
}
