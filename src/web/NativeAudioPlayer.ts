import type { AudioPlayer as AudioPlayerSpec } from '../specs/audio-player.nitro'
import type { AudioBrowser as AudioBrowserSpec } from '../specs/audio-browser.nitro'

import type {
  PlaybackErrorEvent,
  PlaybackError,
  RepeatMode as RepeatModeType,
  PlaybackProgressUpdatedEvent,
  PlaybackQueueEndedEvent,
  PlayingState,
  RepeatModeChangedEvent,
  NativeUpdateOptions,
  UpdateOptions,
  Options,
  Playback,
  PlaybackActiveTrackChangedEvent,
  AudioMetadataReceivedEvent,
  AudioCommonMetadataReceivedEvent,
  PlaybackMetadata,
  PlaybackPlayWhenReadyChangedEvent,
  RemoteJumpBackwardEvent,
  RemoteJumpForwardEvent,
  RemotePlayIdEvent,
  RemotePlaySearchEvent,
  RemoteSeekEvent,
  RemoteSetRatingEvent,
  RemoteSkipEvent,
} from '../features'
import type { Track } from '../types'
import { PlaylistPlayer, RepeatMode } from './TrackPlayer'
import { SetupNotCalledError } from './TrackPlayer/SetupNotCalledError'

type InternalOptions = UpdateOptions & {
  repeatMode: RepeatModeType
}

type WebAudioPlayerSpec = Omit<AudioPlayerSpec, 'name' | 'equals' | 'dispose'>

export class NativeAudioPlayer
  extends PlaylistPlayer
  implements WebAudioPlayerSpec
{
  protected progressUpdateEventInterval: NodeJS.Timeout | undefined
  protected options: InternalOptions = {
    forwardJumpInterval: 15,
    backwardJumpInterval: 15,
    progressUpdateEventInterval: null,
    repeatMode: RepeatMode.Off,
    capabilities: [], // irrelevant in web-world
  }

  // MARK: Event callbacks
  onMetadataChapterReceived: (event: AudioMetadataReceivedEvent) => void =
    () => {}
  onMetadataCommonReceived: (event: AudioCommonMetadataReceivedEvent) => void =
    () => {}
  onMetadataTimedReceived: (event: AudioMetadataReceivedEvent) => void =
    () => {}
  onPlaybackMetadata: (data: PlaybackMetadata) => void = () => {}
  onPlaybackActiveTrackChanged: (
    data: PlaybackActiveTrackChangedEvent
  ) => void = () => {}
  onPlaybackError: (data: PlaybackErrorEvent) => void = () => {}
  onPlaybackPlayWhenReadyChanged: (
    data: PlaybackPlayWhenReadyChangedEvent
  ) => void = () => {}
  onPlaybackPlayingState: (data: PlayingState) => void = () => {}
  onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) => void =
    () => {}
  onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) => void = () => {}
  onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) => void = () => {}
  onPlaybackChanged: (data: Playback) => void = () => {}
  onRemoteBookmark: () => void = () => {}
  onRemoteDislike: () => void = () => {}
  onRemoteJumpBackward: (event: RemoteJumpBackwardEvent) => void = () => {}
  onRemoteJumpForward: (event: RemoteJumpForwardEvent) => void = () => {}
  onRemoteLike: () => void = () => {}
  onRemoteNext: () => void = () => {}
  onRemotePause: () => void = () => {}
  onRemotePlay: () => void = () => {}
  onRemotePlayId: (event: RemotePlayIdEvent) => void = () => {}
  onRemotePlaySearch: (event: RemotePlaySearchEvent) => void = () => {}
  onRemotePrevious: () => void = () => {}
  onRemoteSeek: (event: RemoteSeekEvent) => void = () => {}
  onRemoteSetRating: (event: RemoteSetRatingEvent) => void = () => {}
  onRemoteSkip: (event: RemoteSkipEvent) => void = () => {}
  onRemoteStop: () => void = () => {}
  onOptionsChanged: (event: Options) => void = () => {}

  // MARK: Remote handlers
  handleRemoteBookmark: (() => void) | undefined = undefined
  handleRemoteDislike: (() => void) | undefined = undefined
  handleRemoteJumpBackward:
    | ((event: RemoteJumpBackwardEvent) => void)
    | undefined = undefined
  handleRemoteJumpForward:
    | ((event: RemoteJumpForwardEvent) => void)
    | undefined = undefined
  handleRemoteLike: (() => void) | undefined = undefined
  handleRemoteNext: (() => void) | undefined = undefined
  handleRemotePause: (() => void) | undefined = undefined
  handleRemotePlay: (() => void) | undefined = undefined
  handleRemotePlayId: ((event: RemotePlayIdEvent) => void) | undefined =
    undefined
  handleRemotePlaySearch: ((event: RemotePlaySearchEvent) => void) | undefined =
    undefined
  handleRemotePrevious: (() => void) | undefined = undefined
  handleRemoteSeek: ((event: RemoteSeekEvent) => void) | undefined = undefined
  handleRemoteSetRating: ((event: RemoteSetRatingEvent) => void) | undefined =
    undefined
  handleRemoteSkip: (() => void) | undefined = undefined
  handleRemoteStop: (() => void) | undefined = undefined

  // observe and emit state changes
  protected get state(): Playback {
    return super.state
  }

  protected set state(newState: Playback) {
    const oldState = super.state
    const didStateChange = newState.state !== oldState.state
    const didErrorChange =
      newState.state === 'error' && oldState.state === 'error'
        ? newState.error !== oldState.error
        : false

    super.state = newState

    if (!didStateChange && !didErrorChange) {
      return
    }

    // Call callbacks
    this.onPlaybackChanged(newState)
    this.onPlaybackPlayingState(this.getPlayingStateFromPlayback(newState))

    if (newState.state === 'error' && newState.error) {
      this.onPlaybackError({ error: newState.error })
    }
  }

  private getPlayingStateFromPlayback(playback: Playback): PlayingState {
    return {
      playing: playback.state === 'playing',
      buffering: playback.state === 'buffering',
    }
  }

  protected setupProgressUpdates(interval?: number) {
    // clear and reset interval
    this.clearUpdateEventInterval()
    if (interval) {
      this.clearUpdateEventInterval()
      this.progressUpdateEventInterval = setInterval(() => {
        if (this.state.state === 'playing') {
          const progress = this.getProgress()
          const event: PlaybackProgressUpdatedEvent = {
            ...progress,
            track: this.currentIndex || 0,
          }
          this.onPlaybackProgressUpdated(event)
        }
      }, interval * 1000)
    }
  }

  protected clearUpdateEventInterval() {
    if (this.progressUpdateEventInterval) {
      clearInterval(this.progressUpdateEventInterval)
    }
  }

  protected onPlaylistEnded() {
    super.onPlaylistEnded()
    this.onPlaybackQueueEnded({
      track: this.currentIndex ?? 0,
      position: this.element!.currentTime,
    })
  }

  /****************************************
   * MARK: init and config
   ****************************************/
  // setupPlayer is inherited from Player

  public updateOptions(options: NativeUpdateOptions): void {
    if (
      options.progressUpdateEventInterval !== null ||
      options.progressUpdateEventInterval !== undefined ||
      typeof options.progressUpdateEventInterval !== 'number'
    ) {
      throw new Error('NullSentinal type is not valid on web.')
    }

    // Merge platform-agnostic options
    const mergedOptions: InternalOptions = {
      ...this.options,
      forwardJumpInterval:
        options.forwardJumpInterval ?? this.options.forwardJumpInterval,
      backwardJumpInterval:
        options.backwardJumpInterval ?? this.options.backwardJumpInterval,
      progressUpdateEventInterval:
        options.progressUpdateEventInterval !== undefined
          ? options.progressUpdateEventInterval
          : this.options.progressUpdateEventInterval,
      capabilities: options.capabilities ?? this.options.capabilities,
    }

    this.options = mergedOptions

    // Update progress interval if specified
    if (options.progressUpdateEventInterval !== undefined) {
      this.setupProgressUpdates(
        options.progressUpdateEventInterval === null
          ? undefined
          : options.progressUpdateEventInterval
      )
    }

    // Call callback with full Options type (including repeatMode)
    const fullOptions: Options = {
      forwardJumpInterval: mergedOptions.forwardJumpInterval || 15,
      backwardJumpInterval: mergedOptions.backwardJumpInterval || 15,
      progressUpdateEventInterval:
        mergedOptions.progressUpdateEventInterval || 15,
      capabilities: mergedOptions.capabilities || [],
      repeatMode: mergedOptions.repeatMode,
    }
    this.onOptionsChanged(fullOptions)
  }

  public getOptions(): UpdateOptions {
    return {
      forwardJumpInterval: this.options.forwardJumpInterval,
      backwardJumpInterval: this.options.backwardJumpInterval,
      progressUpdateEventInterval: this.options.progressUpdateEventInterval,
      capabilities: this.options.capabilities,
    }
  }

  public registerBrowser(browser: AudioBrowserSpec): void {
    // Establish bidirectional connection between player and browser
    if ('setAudioPlayer' in browser && typeof browser.setAudioPlayer === 'function') {
      ;(browser as any).setAudioPlayer(this)
    }
  }

  /****************************************
   * MARK: player api
   ****************************************/
  public load(track: Track): void {
    if (!this.element) throw new SetupNotCalledError()
    const lastTrack = this.current
    const lastPosition = this.element.currentTime
    const lastIndex = this.lastIndex
    const currentIndex = this.currentIndex

    super.load(track, () => {
      this.onPlaybackActiveTrackChanged({
        lastTrack,
        lastPosition,
        lastIndex,
        index: currentIndex,
        track,
      })
    })
  }

  // reset is inherited from PlaylistPlayer

  // play is inherited from Player

  // pause is inherited from Player

  public togglePlayback(): void {
    super.togglePlayback()
  }

  // stop is inherited from PlaylistPlayer

  public setPlayWhenReady(pwr: boolean): void {
    const didChange = pwr !== this._playWhenReady
    super.playWhenReady = pwr

    if (didChange) {
      this.onPlaybackPlayWhenReadyChanged({
        playWhenReady: this._playWhenReady,
      })
    }
  }

  public getPlayWhenReady(): boolean {
    return super.playWhenReady
  }

  // seekTo is inherited from Player

  // seekBy is inherited from Player

  // setVolume is inherited from Player

  // getVolume is inherited from Player

  // setRate is inherited from Player

  // getRate is inherited from Player

  // getProgress is inherited from Player

  public getPlayback(): Playback {
    return this.state
  }

  public getPlayingState(): PlayingState {
    return this.getPlayingStateFromPlayback(this.state)
  }

  public getRepeatMode(): RepeatModeType {
    return super.getRepeatMode()
  }

  public setRepeatMode(mode: RepeatModeType): void {
    const didChange = this.repeatMode !== mode
    super.setRepeatMode(mode)

    if (didChange) {
      this.onPlaybackRepeatModeChanged({
        repeatMode: mode,
      })
    }
  }

  public getPlaybackError(): PlaybackError | null {
    if (this.state.state === 'error') {
      return this.state.error || null
    }
    return null
  }

  // retry is inherited from Player

  /****************************************
   * MARK: playlist management
   ****************************************/
  // add is inherited from PlaylistPlayer

  // move is inherited from PlaylistPlayer

  // remove is inherited from PlaylistPlayer

  // removeUpcomingTracks is inherited from PlaylistPlayer

  // skip is inherited from PlaylistPlayer

  // skipToNext is inherited from PlaylistPlayer

  // skipToPrevious is inherited from PlaylistPlayer

  // updateMetadataForTrack is inherited from PlaylistPlayer

  // updateNowPlayingMetadata is inherited from PlaylistPlayer

  public setQueue(queue: Track[]): void {
    this.stop()
    this.playlist = queue
  }

  public getQueue(): Track[] {
    return this.playlist
  }

  // getTrack is inherited from PlaylistPlayer

  public getActiveTrackIndex(): number | undefined {
    // per the existing spec, this should throw if setup hasn't been called
    if (!this.element || !this.player) throw new SetupNotCalledError()
    return this.currentIndex
  }

  public getActiveTrack(): Track | undefined {
    return this.current
  }
}
