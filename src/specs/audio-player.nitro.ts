import { type HybridObject } from 'react-native-nitro-modules'

import type { FavoriteChangedEvent } from '../features'
import type {
  PlaybackActiveTrackChangedEvent
} from '../features/activeTrack'
import type { PlaybackError, PlaybackErrorEvent } from '../features/errors'
import type {
  AudioCommonMetadataReceivedEvent,
  AudioMetadataReceivedEvent,
  PlaybackMetadata
} from '../features/metadata'
import type {
  NativeUpdateOptions,
  Options,
  UpdateOptions
} from '../features/options'
import type { Playback } from '../features/playbackState'
import type { PartialSetupPlayerOptions } from '../features/player'
import type { PlayingState } from '../features/playingState'
import type { PlaybackPlayWhenReadyChangedEvent } from '../features/playWhenReady'
import type {
  PlaybackProgressUpdatedEvent,
  Progress
} from '../features/progress'
import type { PlaybackQueueEndedEvent } from '../features/queue'
import type {
  RemoteJumpBackwardEvent,
  RemoteJumpForwardEvent,
  RemotePlayIdEvent,
  RemotePlaySearchEvent,
  RemoteSeekEvent,
  RemoteSetRatingEvent,
  RemoteSkipEvent
} from '../features/remoteControls'
import type { RepeatMode, RepeatModeChangedEvent } from '../features/repeatMode'
import type { SleepTimer, SleepTimerChangedEvent } from '../features/sleepTimer'
import type { Track } from '../types'
import type { AudioBrowser } from './audio-browser.nitro'

export type EqualizerSettings = {
  activePreset?: string
  bandCount: number
  bandLevels: number[]
  centerBandFrequencies: number[]
  enabled: boolean
  lowerBandLevelLimit: number
  presets: string[]
  upperBandLevelLimit: number
}

export interface AudioPlayer
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // MARK: init and config
  setupPlayer(options: PartialSetupPlayerOptions): Promise<void>
  updateOptions(options: NativeUpdateOptions): void
  getOptions(): UpdateOptions
  registerBrowser(browser: AudioBrowser): void

  // // MARK: events
  onMetadataChapterReceived: (event: AudioMetadataReceivedEvent) => void
  onMetadataCommonReceived: (event: AudioCommonMetadataReceivedEvent) => void
  onMetadataTimedReceived: (event: AudioMetadataReceivedEvent) => void
  onPlaybackMetadata: (data: PlaybackMetadata) => void
  onPlaybackActiveTrackChanged: (data: PlaybackActiveTrackChangedEvent) => void
  onPlaybackError: (data: PlaybackErrorEvent) => void
  onPlaybackPlayWhenReadyChanged: (
    data: PlaybackPlayWhenReadyChangedEvent
  ) => void
  onPlaybackPlayingState: (data: PlayingState) => void
  onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) => void
  onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) => void
  onPlaybackQueueChanged: (queue: Track[]) => void
  onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) => void
  onSleepTimerChanged: (data: SleepTimerChangedEvent) => void
  onPlaybackChanged: (data: Playback) => void
  onRemoteBookmark: () => void
  onRemoteDislike: () => void
  onRemoteJumpBackward: (event: RemoteJumpBackwardEvent) => void
  onRemoteJumpForward: (event: RemoteJumpForwardEvent) => void
  onRemoteLike: () => void
  onRemoteNext: () => void
  onRemotePause: () => void
  onRemotePlay: () => void
  onRemotePlayId: (event: RemotePlayIdEvent) => void
  onRemotePlaySearch: (event: RemotePlaySearchEvent) => void
  onRemotePrevious: () => void
  onRemoteSeek: (event: RemoteSeekEvent) => void
  onRemoteSetRating: (event: RemoteSetRatingEvent) => void
  onRemoteSkip: (event: RemoteSkipEvent) => void
  onRemoteStop: () => void
  onOptionsChanged: (event: Options) => void
  onFavoriteChanged: (event: FavoriteChangedEvent) => void

  // MARK: remote handlers
  handleRemoteBookmark: (() => void) | undefined
  handleRemoteDislike: (() => void) | undefined
  handleRemoteJumpBackward:
    | ((event: RemoteJumpBackwardEvent) => void)
    | undefined
  handleRemoteJumpForward: ((event: RemoteJumpForwardEvent) => void) | undefined
  handleRemoteLike: (() => void) | undefined
  handleRemoteNext: (() => void) | undefined
  handleRemotePause: (() => void) | undefined
  handleRemotePlay: (() => void) | undefined
  handleRemotePlayId: ((event: RemotePlayIdEvent) => void) | undefined
  handleRemotePlaySearch: ((event: RemotePlaySearchEvent) => void) | undefined
  handleRemotePrevious: (() => void) | undefined
  handleRemoteSeek: ((event: RemoteSeekEvent) => void) | undefined
  handleRemoteSkip: (() => void) | undefined
  handleRemoteStop: (() => void) | undefined

  // MARK: player api
  load(track: Track): void
  reset(): void
  play(): void
  pause(): void
  togglePlayback(): void
  stop(): void
  setPlayWhenReady(playWhenReady: boolean): void
  getPlayWhenReady(): boolean
  seekTo(position: number): void
  seekBy(offset: number): void
  setVolume(level: number): void
  getVolume(): number
  setRate(rate: number): void
  getRate(): number
  getProgress(): Progress
  getPlayback(): Playback
  getPlayingState(): PlayingState
  getRepeatMode(): RepeatMode
  setRepeatMode(mode: RepeatMode): void
  getPlaybackError(): PlaybackError | undefined
  retry(): void
  getSleepTimer(): SleepTimer
  setSleepTimer(seconds: number): void
  setSleepTimerToEndOfTrack(): void
  clearSleepTimer(): boolean

  // MARK: playlist management
  add(tracks: Track[], insertBeforeIndex?: number): void
  move(fromIndex: number, toIndex: number): void
  remove(indexes: number[]): void
  removeUpcomingTracks(): void
  skip(index: number, initialPosition?: number): void
  skipToNext(initialPosition?: number): void
  skipToPrevious(initialPosition?: number): void
  /**
   * Sets the favorited state of the currently playing track.
   * Updates the heart icon in media controllers (notification, Android Auto, CarPlay).
   */
  setActiveTrackFavorited(favorited: boolean): void
  /**
   * Toggles the favorited state of the currently playing track.
   */
  toggleActiveTrackFavorited(): void
  setQueue(tracks: Track[], startIndex?: number, startPositionMs?: number): void
  getQueue(): Track[]
  getTrack(index: number): Track | undefined
  getActiveTrackIndex(): number | undefined
  getActiveTrack(): Track | undefined

  // MARK: network connectivity
  getOnline(): boolean
  onOnlineChanged: (online: boolean) => void

  // MARK: equalizer (Android only)
  getEqualizerSettings(): EqualizerSettings | undefined
  setEqualizerEnabled(enabled: boolean): void
  setEqualizerPreset(preset: string): void
  setEqualizerLevels(levels: number[]): void
  onEqualizerChanged: (settings: EqualizerSettings) => void
}
