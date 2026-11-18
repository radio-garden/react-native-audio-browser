package com.audiobrowser

import com.audiobrowser.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.ChapterMetadata
import com.margelo.nitro.audiobrowser.EqualizerSettings
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.SleepTimer
import com.margelo.nitro.audiobrowser.TimedMetadata
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackMetadata

interface Callbacks {
  fun onPlaybackChanged(playback: Playback)

  fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent)

  fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent)

  fun onPlaybackInterval()

  fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent)

  fun onPlaybackPlayingState(event: PlayingState)

  fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent)

  fun onPlaybackQueueChanged(queue: Array<Track>)

  fun onPlaybackRepeatModeChanged(event: RepeatMode)

  fun onPlaybackShuffleModeChanged(enabled: Boolean)

  fun onPlaybackError(error: PlaybackError?)

  fun onTrackMetadata(metadata: TrackMetadata)

  fun onChapterMetadata(chapters: List<ChapterMetadata>)

  fun onTimedMetadata(metadata: TimedMetadata)

  // handleRemote*: true when JS handled the command, so the player skips its default action.
  fun handleRemotePlay(): Boolean

  fun handleRemotePause(): Boolean

  fun handleRemoteStop(): Boolean

  fun handleRemoteNext(): Boolean

  fun handleRemotePrevious(): Boolean

  fun handleRemoteJumpForward(event: RemoteJumpForwardEvent): Boolean

  fun handleRemoteJumpBackward(event: RemoteJumpBackwardEvent): Boolean

  fun handleRemoteSeek(event: RemoteSeekEvent): Boolean

  fun onOptionsChanged(options: PlayerUpdateOptions)

  fun onFavoriteChanged(event: FavoriteChangedEvent)

  fun onNowPlayingChanged(metadata: NowPlayingMetadata)

  fun onOnlineChanged(online: Boolean)

  fun onEqualizerChanged(settings: EqualizerSettings)

  fun onSleepTimerChanged(timer: SleepTimer?)
}
