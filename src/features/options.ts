import TrackPlayer from '../NativeTrackPlayer'
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue'
import type { RatingType } from './metadata'
import type { RepeatMode } from './repeatMode'

// MARK: - Types

export type Capability =
  | 'play'
  | 'play-from-id'
  | 'play-from-search'
  | 'pause'
  | 'stop'
  | 'seek-to'
  | 'skip'
  | 'skip-to-next'
  | 'skip-to-previous'
  | 'jump-forward'
  | 'jump-backward'
  | 'set-rating'

/**
 * AppKilledPlaybackBehavior options:
 * - `'continue-playback'`: This option will continue playing audio in the
 *   background when the app is removed from recents. The notification remains.
 *   This is the default.
 * - `'pause-playback'`: This option will pause playing audio in the background
 *   when the app is removed from recents. The notification remains and can be
 *   used to resume playback.
 * - `'stop-playback-and-remove-notification'`: This option will stop playing
 *   audio in the background when the app is removed from recents. The
 *   notification is removed and can't be used to resume playback. Users would
 *   need to open the app again to start playing audio.
 */
export type AppKilledPlaybackBehavior =
  | 'continue-playback'
  | 'pause-playback'
  | 'stop-playback-and-remove-notification'

/**
 * Current player options with resolved defaults.
 * This is what you receive from getOptions() - all properties are present with their current values.
 * Platform-specific properties (android/ios) are only available on their respective platforms.
 *
 * @example
 * ```typescript
 * const options = getOptions();
 * console.log(options.repeatMode); // 'off'
 * console.log(options.capabilities); // ['play', 'pause', 'skip-to-next', 'skip-to-previous']
 * console.log(options.android?.skipSilence); // true (Android only)
 * console.log(options.ios?.likeOptions); // { isActive: false, title: 'Like' } (iOS only)
 * ```
 */
export interface Options {
  /** Android-specific configuration options with resolved defaults (only present on Android) */
  android?: AndroidUpdateOptions

  /** iOS-specific configuration options with resolved defaults (only present on iOS) */
  ios?: IOSUpdateOptions

  /**
   * Jump forward interval in seconds when using jump forward controls.
   * @default 15
   */
  forwardJumpInterval: number

  /**
   * Jump backward interval in seconds when using jump backward controls.
   * @default 15
   */
  backwardJumpInterval: number

  /**
   * How often progress events are emitted in seconds.
   * When null, progress events are disabled.
   * @default null
   */
  progressUpdateEventInterval: number | null

  /**
   * The capabilities that the player has.
   * @default [Capability.Play, Capability.Pause, Capability.SkipToNext, Capability.SkipToPrevious, Capability.SeekTo]
   */
  capabilities: Capability[]

  /**
   * The current repeat mode of the player.
   * @default RepeatMode.Off
   */
  repeatMode: RepeatMode
}

export interface FeedbackOptions {
  /** Marks wether the option should be marked as active or "done" */
  isActive: boolean

  /** The title to give the action (relevant for iOS) */
  title: string
}

export interface AndroidUpdateOptions {
  /**
   * Whether the audio playback notification is also removed when the playback
   * stops. **If `stoppingAppPausesPlayback` is set to false, this will be
   * ignored.**
   */
  appKilledPlaybackBehavior: AppKilledPlaybackBehavior

  /**
   * Whether to automatically skip silent audio segments during playback.
   * When enabled, the player will detect and skip over periods of silence.
   *
   * @default false
   */
  skipSilence: boolean

  /**
   * Whether shuffle mode is enabled for queue playback.
   * When enabled, tracks will be played in random order.
   *
   * @default false
   */
  shuffle: boolean

  /**
   * The rating type to use for ratings.
   * Determines how star ratings and thumbs up/down are handled.
   */
  ratingType: RatingType

  /**
   * Android-specific capabilities that control which buttons appear in
   * notifications only. This does NOT affect other controllers like
   * Bluetooth, Android Auto, or lock screen.
   *
   * When null, defaults to the global capabilities.
   * Use an empty array to show no notification buttons.
   *
   * @platform android
   */
  notificationCapabilities: Capability[] | null
}

export interface IOSUpdateOptions {
  /**
   * Configuration for the like/heart button in iOS control center.
   * Only available on iOS.
   * @default { isActive: false, title: "Like" }
   */
  likeOptions: FeedbackOptions

  /**
   * Configuration for the dislike button in iOS control center.
   * Only available on iOS.
   * @default { isActive: false, title: "Dislike" }
   */
  dislikeOptions: FeedbackOptions

  /**
   * Configuration for the bookmark button in iOS control center.
   * Only available on iOS.
   * @default { isActive: false, title: "Bookmark" }
   */
  bookmarkOptions: FeedbackOptions
}

/**
 * Partial options for updating player configuration.
 * Only specify the properties you want to change - all properties are optional.
 * Use null to reset properties to their defaults.
 *
 * @example
 * ```typescript
 * // Update only repeat mode
 * updateOptions({ repeatMode: 'track' });
 *
 * // Update multiple properties
 * updateOptions({
 *   repeatMode: 'queue',
 *   capabilities: ['play', 'pause', 'skip-to-next']
 * });
 *
 * // Disable progress events by setting to null
 * updateOptions({
 *   progressUpdateEventInterval: null
 * });
 *
 * // Platform-specific options
 * updateOptions({
 *   android: { skipSilence: true },
 *   ios: { likeOptions: { isActive: true, title: 'Love' } }
 * });
 * ```
 */
export interface UpdateOptions {
  /** Android-specific configuration options */
  android?: Partial<AndroidUpdateOptions>

  /** iOS-specific configuration options */
  ios?: Partial<IOSUpdateOptions>

  /**
   * Jump forward interval in seconds when using jump forward controls.
   * @default 15
   */
  forwardJumpInterval?: number

  /**
   * Jump backward interval in seconds when using jump backward controls.
   * @default 15
   */
  backwardJumpInterval?: number

  /**
   * How often progress events are emitted in seconds.
   * @default 1
   */
  progressUpdateEventInterval?: number

  capabilities?: Capability[]
}

// MARK: - Functions

/**
 * Updates the configuration for the components.
 * Pass only the properties you want to change - all properties are optional.
 *
 * @param options - The partial options to update. Only changed properties need to be specified.
 * @see https://rntp.dev/docs/api/functions/player#updateoptionsoptions
 * @example
 * ```typescript
 * // Update single property
 * updateOptions({ repeatMode: 'track' });
 *
 * // Update multiple properties
 * updateOptions({
 *   capabilities: ['play', 'pause'],
 *   progressUpdateEventInterval: 0.5
 * });
 * ```
 */
export function updateOptions(options: UpdateOptions): void {
  TrackPlayer.updateOptions(options)
}

// MARK: - Getters

/**
 * Gets the current player options with resolved defaults.
 * Returns all current option values - use this to read the current state.
 *
 * @returns The current player options with all properties resolved to their current values
 * @example
 * ```typescript
 * const options = getOptions();
 * if (options.repeatMode === 'track') {
 *   // Handle track repeat mode
 * }
 * ```
 */
export function getOptions(): Options {
  return TrackPlayer.getOptions() as Options
}

// MARK: - Event Callbacks

/**
 * Subscribes to player options changes.
 * @param callback - Called when the player options change
 * @returns Cleanup function to unsubscribe
 */
export function onOptionsChanged(
  callback: (options: Options) => void
): () => void {
  return TrackPlayer.onOptionsChanged(callback as () => void).remove
}

// MARK: - Hooks

/**
 * Hook that returns the current player options and updates when they change.
 * @returns The current player options
 */
export function useOptions(): Options {
  return useUpdatedNativeValue(getOptions, onOptionsChanged)
}
