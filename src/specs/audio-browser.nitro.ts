import { type HybridObject } from 'react-native-nitro-modules'

import type {
  BatteryOptimizationStatus,
  BatteryOptimizationStatusChangedEvent,
  BatteryWarningPendingChangedEvent,
  FavoriteChangedEvent
} from '../features'
import type { NavigationError, NavigationErrorEvent, PlaybackError, PlaybackErrorEvent } from '../features/errors'
import type {
  AudioCommonMetadataReceivedEvent,
  AudioMetadataReceivedEvent,
  NowPlayingMetadata,
  NowPlayingUpdate,
  PlaybackMetadata
} from '../features/metadata'
import type { PlayingState } from '../features/playback/playing'
import type { PlaybackPlayWhenReadyChangedEvent } from '../features/playback/playWhenReady'
import type {
  PlaybackProgressUpdatedEvent,
  Progress
} from '../features/playback/progress'
import type { Playback } from '../features/playback/state'
import type {
  NativeUpdateOptions,
  Options,
  PartialSetupPlayerOptions,
  UpdateOptions
} from '../features/player'
import type {
  PlaybackActiveTrackChangedEvent
} from '../features/queue/activeTrack'
import type { PlaybackQueueEndedEvent } from '../features/queue/queue'
import type { RepeatMode, RepeatModeChangedEvent } from '../features/queue/repeatMode'
import type {
  RemoteJumpBackwardEvent,
  RemoteJumpForwardEvent,
  RemotePlayIdEvent,
  RemotePlaySearchEvent,
  RemoteSeekEvent,
  RemoteSetRatingEvent,
  RemoteSkipEvent
} from '../features/remoteControls'
import type { SleepTimer, SleepTimerChangedEvent } from '../features/sleepTimer'
import type { ResolvedTrack, Track } from '../types'
import type { NativeBrowserConfiguration } from '../types/browser-native'

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

export interface AudioBrowser
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {

  // MARK: browser api
  path: string | undefined
  tabs: Track[] | undefined
  navigatePath(path: string): void
  navigateTrack(track: Track): void
  onSearch(query: string): Promise<Track[]>
  getContent(): ResolvedTrack | undefined
  onPathChanged: (path: string) => void
  onContentChanged: (content: ResolvedTrack | undefined) => void
  onTabsChanged: (tabs: Track[]) => void
  onNavigationError: (data: NavigationErrorEvent) => void
  getNavigationError(): NavigationError | undefined
  notifyContentChanged(path: string): void
  setFavorites(favorites: string[]): void
  configuration: NativeBrowserConfiguration

  // MARK: player init and config
  setupPlayer(options: PartialSetupPlayerOptions): Promise<void>
  updateOptions(options: NativeUpdateOptions): void
  getOptions(): UpdateOptions

  // // MARK: player events
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
  onPlaybackShuffleModeChanged: (enabled: boolean) => void
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
  onNowPlayingChanged: (metadata: NowPlayingMetadata) => void

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
  getShuffleEnabled(): boolean
  setShuffleEnabled(enabled: boolean): void
  getPlaybackError(): PlaybackError | undefined
  retry(): void
  getSleepTimer(): SleepTimer
  setSleepTimer(seconds: number): void
  setSleepTimerToEndOfTrack(): void
  clearSleepTimer(): boolean

  // MARK: queue management
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

  // MARK: now playing metadata
  /**
   * Updates the now playing notification metadata.
   * Pass null to clear overrides and revert to track metadata.
   */
  updateNowPlaying(update: NowPlayingUpdate | undefined): void
  /**
   * Gets the current now playing metadata (override if set, else track metadata).
   */
  getNowPlaying(): NowPlayingMetadata | undefined

  // MARK: network connectivity
  getOnline(): boolean
  onOnlineChanged: (online: boolean) => void

  // MARK: equalizer (Android only)
  getEqualizerSettings(): EqualizerSettings | undefined
  setEqualizerEnabled(enabled: boolean): void
  setEqualizerPreset(preset: string): void
  setEqualizerLevels(levels: number[]): void
  onEqualizerChanged: (settings: EqualizerSettings) => void

  // MARK: battery optimization (Android only)
  /**
   * Check if a battery warning is pending.
   * Returns true if a foreground service start was blocked and the user hasn't dismissed
   * the warning or fixed their battery settings.
   * Auto-clears when battery status becomes unrestricted.
   * Always returns false on iOS.
   */
  getBatteryWarningPending(): boolean
  /**
   * Get the current battery optimization status.
   * - `unrestricted`: App can run freely in background
   * - `optimized`: System may limit background work (default)
   * - `restricted`: Background services blocked
   * Always returns `unrestricted` on iOS.
   */
  getBatteryOptimizationStatus(): BatteryOptimizationStatus
  /**
   * Dismiss the battery warning without fixing settings.
   * Call this when the user chooses to ignore the warning.
   * No-op on iOS.
   */
  dismissBatteryWarning(): void
  /**
   * Open the system battery settings for this app.
   * No-op on iOS.
   */
  openBatterySettings(): void
  /**
   * Called when battery warning pending state changes.
   * Fires when: failure occurs (true), dismissBatteryWarning() called (false),
   * or status becomes unrestricted (false).
   * Never fires on iOS.
   */
  onBatteryWarningPendingChanged: (
    event: BatteryWarningPendingChangedEvent
  ) => void
  /**
   * Called when battery optimization status changes.
   * Fires when user returns from settings with a different status.
   * Never fires on iOS.
   */
  onBatteryOptimizationStatusChanged: (
    event: BatteryOptimizationStatusChangedEvent
  ) => void
}
