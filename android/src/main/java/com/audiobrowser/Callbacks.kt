package com.audiobrowser

import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.model.TimedMetadata
import com.margelo.nitro.audiobrowser.AudioMetadata
import com.margelo.nitro.audiobrowser.EqualizerSettings
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.SleepTimer

/** Callbacks for all player events. */
interface Callbacks {
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

  // Rating events (listener only, not handler)
  fun onRemoteSetRating(event: RemoteSetRatingEvent)

  // Configuration events
  fun onOptionsChanged(options: PlayerUpdateOptions)

  // Favorite events
  fun onFavoriteChanged(event: FavoriteChangedEvent)

  // Network connectivity events
  fun onOnlineChanged(online: Boolean)

  // Equalizer events
  fun onEqualizerChanged(settings: EqualizerSettings)

  // Sleep timer events
  fun onSleepTimerChanged(timer: SleepTimer?)
}
