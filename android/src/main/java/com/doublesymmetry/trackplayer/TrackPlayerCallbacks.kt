package com.doublesymmetry.trackplayer

import androidx.media3.common.Metadata
import com.doublesymmetry.trackplayer.event.PlaybackActiveTrackChangedEvent
import com.doublesymmetry.trackplayer.event.PlaybackErrorEvent
import com.doublesymmetry.trackplayer.event.PlaybackPlayWhenReadyChangedEvent
import com.doublesymmetry.trackplayer.event.PlaybackPlayingStateEvent
import com.doublesymmetry.trackplayer.event.PlaybackProgressUpdatedEvent
import com.doublesymmetry.trackplayer.event.PlaybackQueueEndedEvent
import com.doublesymmetry.trackplayer.event.PlaybackRepeatModeChangedEvent
import com.doublesymmetry.trackplayer.event.RemoteJumpBackwardEvent
import com.doublesymmetry.trackplayer.event.RemoteJumpForwardEvent
import com.doublesymmetry.trackplayer.event.RemoteSeekEvent
import com.doublesymmetry.trackplayer.event.RemoteSetRatingEvent
import com.doublesymmetry.trackplayer.model.CommonMetadata
import com.doublesymmetry.trackplayer.model.PlaybackMetadata
import com.doublesymmetry.trackplayer.model.PlaybackState
import com.doublesymmetry.trackplayer.model.PlayerUpdateOptions
import com.doublesymmetry.trackplayer.model.TimedMetadata
import com.margelo.nitro.audiobrowser.AudioMetadata

/** Callbacks for all player events. */
interface TrackPlayerCallbacks {
  // Playback state events
  fun onPlaybackState(state: PlaybackState)

  fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent)

  fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent)

  fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent)

  fun onPlaybackPlayingState(event: PlaybackPlayingStateEvent)

  fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent)

  fun onPlaybackRepeatModeChanged(event: PlaybackRepeatModeChangedEvent)

  fun onPlaybackError(event: PlaybackErrorEvent)

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
