import { type HybridObject } from 'react-native-nitro-modules'
import type {
  BatteryOptimizationStatus,
  BatteryOptimizationStatusChangedEvent,
  BatteryWarningPendingChangedEvent,
  FavoriteChangedEvent
} from '../features'
import type {
  NavigationError,
  NavigationErrorEvent,
  FormattedNavigationError,
  PlaybackError,
  PlaybackErrorEvent
} from '../features/errors'
import type {
  ChapterMetadata,
  NowPlayingMetadata,
  NowPlayingUpdate,
  TimedMetadata,
  TrackMetadata
} from '../features/metadata'
import type { PlayingState } from '../features/playback/playing'
import type { PlaybackPlayWhenReadyChangedEvent } from '../features/playback/playWhenReady'
import type {
  PlaybackProgressUpdatedEvent,
  Progress
} from '../features/playback/progress'
import type { Playback } from '../features/playback/state'
import type {
  NativeSetupPlayerOptions,
  NativeUpdateOptions,
  Options
} from '../features/player'
import type { PlaybackActiveTrackChangedEvent } from '../features/queue/activeTrack'
import type { PlaybackQueueEndedEvent } from '../features/queue/queue'
import type {
  RepeatMode,
  RepeatModeChangedEvent
} from '../features/queue/repeatMode'
import type {
  RemoteJumpBackwardEvent,
  RemoteJumpForwardEvent,
  RemotePlayIdEvent,
  RemotePlaySearchEvent,
  RemoteSeekEvent,
  RemoteSkipEvent
} from '../features/remoteControls'
import type { NativeGate, NativeGateRequest, GateDecision, GateEvent } from '../features/gate'
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

/**
 * Cross-platform audio output kind. Each platform maps its native ports into
 * this shared set: iOS's granular Bluetooth ports (`bluetoothA2DP`/`HFP`/`LE`)
 * all collapse to `'bluetooth'`. Note Android's *reading* (`getOutput`) reports
 * the active local route only — `'airplay'`/`'cast'` come from iOS / are
 * reserved for remote destinations and don't appear from Android reads.
 */
export type OutputType =
  | 'speaker' // built-in loudspeaker
  | 'receiver' // built-in earpiece (iOS)
  | 'headphones' // wired headphones / headset
  | 'bluetooth' // any Bluetooth audio
  | 'airplay' // AirPlay (iOS)
  | 'car' // car audio (CarPlay / Android Auto / car Bluetooth)
  | 'hdmi'
  | 'usb'
  | 'cast' // remote speaker / TV (reserved)
  | 'other'

/**
 * The current audio output. Read via `getOutput()` / `useOutput()` on every
 * platform: iOS always reports one while a session is active; Android reports the
 * actively-routed device on all versions (the `type` is accurate on API 33+ and
 * coarse below that). Only the output *switcher* (`openOutputPicker`) is gated to
 * Android 11+ — check `supportsOutputSwitcher()`.
 */
export type Output = {
  /** The output kind */
  type: OutputType
  /** Human-readable device name (e.g., "AirPods Pro", "Kitchen speaker") */
  name: string
  /** Whether this is an external output (false for built-in speaker/receiver) */
  external: boolean
}

export interface AudioBrowser extends HybridObject<{
  ios: 'swift'
  android: 'kotlin'
}> {
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
  onFormattedNavigationError: (
    formattedError: FormattedNavigationError | undefined
  ) => void
  getFormattedNavigationError(): FormattedNavigationError | undefined
  notifyContentChanged(path: string): void
  invalidateAllContent(): void
  setFavorites(favorites: string[]): void
  configuration: NativeBrowserConfiguration

  // MARK: gate
  /**
   * Records the gate's default chrome (undefined for resolver-only) and whether
   * a per-request resolver is active. While a gate is set, the four car
   * enforcement sites consult `resolveGate` per request (skipping the JS hop
   * when `hasResolver` is false — every request is gated with the default).
   */
  setGate(gate: NativeGate | undefined, hasResolver: boolean): void
  /** Clears the gate, restoring tab content and keeping selection. */
  clearGate(): void
  /** Per-request decision, set by JS; native awaits it at a serve site. */
  resolveGate: (request: NativeGateRequest) => Promise<GateDecision>
  /** Fired when a request is gated (the gate was served). */
  onGate: (event: GateEvent) => void

  // MARK: car connection
  /**
   * Whether a car is currently connected: a CarPlay scene on iOS, an
   * Android Auto / Android Automotive connection on Android (via the
   * androidx.car.app CarConnection provider).
   */
  isCarConnected(): boolean
  /**
   * Called when the car connects or disconnects.
   */
  onCarConnectedChanged: (connected: boolean) => void

  // MARK: player init and config
  setupPlayer(options: NativeSetupPlayerOptions): Promise<void>
  updateOptions(options: NativeUpdateOptions): void
  getOptions(): Options

  // // MARK: player events
  onChapterMetadata: (chapters: ChapterMetadata[]) => void
  onTrackMetadata: (metadata: TrackMetadata) => void
  onTimedMetadata: (metadata: TimedMetadata) => void
  onPlaybackActiveTrackChanged: (data: PlaybackActiveTrackChangedEvent) => void
  onPlaybackError: (data: PlaybackErrorEvent) => void
  onPlaybackPlayWhenReadyChanged: (
    data: PlaybackPlayWhenReadyChangedEvent
  ) => void
  onPlaybackPlayingState: (data: PlayingState) => void
  onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) => void
  /**
   * Fired on a fixed internal cadence while playback is `playing`, once enabled
   * via `setPlaybackIntervalEnabled`. Carries no payload — it is a tick, not a
   * progress/position update. Independent of `onPlaybackProgressUpdated` and the
   * `progressUpdateEventInterval` option.
   */
  onPlaybackInterval: () => void
  onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) => void
  onPlaybackQueueChanged: (queue: Track[]) => void
  onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) => void
  onPlaybackShuffleModeChanged: (enabled: boolean) => void
  onSleepTimerChanged: (data: SleepTimerChangedEvent) => void
  onPlaybackChanged: (data: Playback) => void
  onRemoteJumpBackward: (event: RemoteJumpBackwardEvent) => void
  onRemoteJumpForward: (event: RemoteJumpForwardEvent) => void
  onRemoteNext: () => void
  onRemotePause: () => void
  onRemotePlay: () => void
  onRemotePlayId: (event: RemotePlayIdEvent) => void
  onRemotePlaySearch: (event: RemotePlaySearchEvent) => void
  onRemotePrevious: () => void
  onRemoteSeek: (event: RemoteSeekEvent) => void
  onRemoteSkip: (event: RemoteSkipEvent) => void
  onRemoteStop: () => void
  onOptionsChanged: (event: Options) => void
  onFavoriteChanged: (event: FavoriteChangedEvent) => void
  onNowPlayingChanged: (metadata: NowPlayingMetadata) => void

  // MARK: remote handlers
  handleRemoteJumpBackward:
    | ((event: RemoteJumpBackwardEvent) => void)
    | undefined
  handleRemoteJumpForward: ((event: RemoteJumpForwardEvent) => void) | undefined
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
  /**
   * Jump to the live edge of the current track. No-op for non-live tracks.
   * Live with a seekable window (HLS): seeks to the window end. Live without a
   * window (non-seekable, e.g. ICY): reconnects to rejoin live.
   */
  seekToLiveEdge(): void
  setVolume(level: number): void
  getVolume(): number
  setRate(rate: number): void
  getRate(): number
  getProgress(): Progress
  /**
   * Enables or disables the internal playback tick that drives
   * `onPlaybackInterval`. When disabled (default), no tick is emitted.
   */
  setPlaybackIntervalEnabled(enabled: boolean): void
  getPlayback(): Playback
  getPlayingState(): PlayingState
  getRepeatMode(): RepeatMode
  setRepeatMode(mode: RepeatMode): void
  getShuffleEnabled(): boolean
  setShuffleEnabled(enabled: boolean): void
  getPlaybackError(): PlaybackError | undefined
  retry(): void
  getSleepTimer(): SleepTimer
  /**
   * Stops playback after `seconds`. When `fadeDuration` is given, the volume
   * ramps down over the final `fadeDuration` seconds so silence lands exactly
   * at the deadline; the pre-fade volume is restored after pausing.
   */
  setSleepTimer(seconds: number, fadeDuration?: number): void
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
   * Temporarily replaces now-playing fields for `durationMs`, then reverts.
   * Outranks the formatter and the `updateNowPlaying` override while active,
   * and is reverted by a native timer (JS timers pause in a backgrounded
   * host). Cleared early on track change. Repeated calls restart the window.
   */
  flashNowPlaying(update: NowPlayingUpdate, durationMs: number): void
  /**
   * Clears an active flash immediately, reverting to the live metadata.
   * No-op when no flash is active.
   */
  clearNowPlayingFlash(): void
  /**
   * Gets the current now playing metadata (override if set, else track metadata).
   */
  getNowPlaying(): NowPlayingMetadata | undefined

  // MARK: network connectivity
  getOnline(): boolean
  onOnlineChanged: (online: boolean) => void

  // MARK: system volume
  /**
   * Gets the current system volume (0.0 to 1.0).
   */
  getSystemVolume(): number
  /**
   * Sets the system volume (0.0 to 1.0).
   * Note: On iOS this is a no-op as Apple doesn't provide a public API to set system volume.
   */
  setSystemVolume(volume: number): void
  /**
   * Called when the system volume changes.
   */
  onSystemVolumeChanged: (volume: number) => void

  // MARK: external audio output
  /**
   * The current audio output, or undefined when unknown. iOS reports one while a
   * session is active; Android reports the actively-routed media device via
   * AudioManager — accurate on API 33+ (reflects manual reroutes); on older
   * Android the `type` is coarse and can't detect a reroute while a device stays
   * connected.
   */
  getOutput(): Output | undefined
  /**
   * Called when the current audio output changes (headphones unplugged, a
   * Bluetooth speaker connected, AirPlay/route selected). Fires on iOS and
   * Android; never on web.
   */
  onOutputChanged: (output: Output) => void
  /**
   * Presents the system audio output switcher so the listener can move playback
   * to another output — Bluetooth, AirPlay/Sonos-via-AirPlay, speaker (iOS), or
   * the Bluetooth / speaker / Cast device list (Android). Cross-platform.
   *
   * iOS: the system route picker (always available).
   * Android: the system Output Switcher (Android 11+); no-op below that — gate
   * on `supportsOutputSwitcher()`.
   * Web: no-op.
   */
  openOutputPicker(): void
  /**
   * Whether `openOutputPicker()` can present a system output switcher on this
   * device — surface the output control in the UI only when this is true.
   * iOS: true. Android: true on Android 11+ (API 30). Web: false.
   */
  supportsOutputSwitcher(): boolean

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
