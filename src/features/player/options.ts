import type { RatingType } from '../metadata'
import type { RepeatMode } from '../queue/repeatMode'
import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'

// MARK: - Types

/**
 * Player capabilities control which media controls are available to the user.
 * All capabilities are enabled by default - only specify ones you want to disable.
 *
 * @example
 * ```typescript
 * // Disable shuffle and repeat for a simple player
 * updateOptions({
 *   capabilities: {
 *     shuffleMode: false,
 *     repeatMode: false,
 *   }
 * })
 * ```
 */
export interface PlayerCapabilities {
  /**
   * Enable play control.
   * @default true
   */
  play?: boolean
  /**
   * Enable pause control.
   * @default true
   */
  pause?: boolean
  /**
   * Enable stop control.
   * @default true
   */
  stop?: boolean
  /**
   * Enable seek-to-position control (scrubbing in timeline).
   * @default true
   */
  seekTo?: boolean
  /**
   * Enable skip to next track control.
   * @default true
   */
  skipToNext?: boolean
  /**
   * Enable skip to previous track control.
   * @default true
   */
  skipToPrevious?: boolean
  /**
   * Enable jump forward control (configurable via forwardJumpInterval).
   * Typically used for podcast/audiobook apps.
   * @default false
   */
  jumpForward?: boolean
  /**
   * Enable jump backward control (configurable via backwardJumpInterval).
   * Typically used for podcast/audiobook apps.
   * @default false
   */
  jumpBackward?: boolean
  /**
   * Enable favorite/like control.
   * On iOS: appears in Control Center.
   * On Android: can be assigned to notification button slots.
   * @default true
   */
  favorite?: boolean
  /**
   * Enable shuffle mode toggle.
   * @default true
   */
  shuffleMode?: boolean
  /**
   * Enable repeat mode toggle.
   * @default true
   */
  repeatMode?: boolean
  /**
   * Enable playback rate control.
   * On iOS: appears in Control Center and CarPlay.
   * @default true
   */
  playbackRate?: boolean
}

/**
 * Buttons that can be assigned to Android notification button slots.
 * These represent the interactive buttons users can tap in the notification.
 */
export type NotificationButton =
  | 'skip-to-previous'
  | 'skip-to-next'
  | 'jump-backward'
  | 'jump-forward'
  | 'favorite'

/**
 * Configuration for notification button layout on Android.
 * Allows explicit control over which buttons appear in which slots.
 *
 * Slot behavior:
 * - **Omit a slot**: Derive from capabilities (smart default)
 * - **Set to null**: Explicitly empty slot
 * - **Set to button**: Show that button in that slot
 *
 * @example
 * ```typescript
 * // Podcast-style: jump buttons as primary
 * notificationButtons: {
 *   back: 'jump-backward',
 *   forward: 'jump-forward',
 *   overflow: ['favorite']
 * }
 *
 * // Music-style: skip as primary, jump as secondary
 * notificationButtons: {
 *   back: 'skip-to-previous',
 *   forward: 'skip-to-next',
 *   backSecondary: 'jump-backward',
 *   forwardSecondary: 'jump-forward'
 * }
 * ```
 */
export type NotificationButtonLayout = {
  /** Primary back position (SLOT_BACK) - typically previous track or jump backward */
  back?: NotificationButton | null
  /** Primary forward position (SLOT_FORWARD) - typically next track or jump forward */
  forward?: NotificationButton | null
  /** Secondary back position (SLOT_BACK_SECONDARY) */
  backSecondary?: NotificationButton | null
  /** Secondary forward position (SLOT_FORWARD_SECONDARY) */
  forwardSecondary?: NotificationButton | null
  /** Additional buttons in overflow area (SLOT_OVERFLOW) */
  overflow?: NotificationButton[]
}

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
 * console.log(options.capabilities); // { shuffleMode: false } - only disabled caps shown
 * console.log(options.android?.skipSilence); // true (Android only)
 * console.log(options.ios?.likeOptions); // { isActive: false, title: 'Like' } (iOS only)
 * ```
 */
export interface Options {
  /** Android-specific configuration options with resolved defaults (only present on Android) */
  android?: AndroidOptions

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
   * All capabilities are enabled by default - this shows which ones are disabled.
   */
  capabilities: PlayerCapabilities

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

export interface AndroidOptions {
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
   * Slot-based button layout for Android notifications.
   * Provides explicit control over which buttons appear in which positions.
   *
   * When not specified, button layout is derived from capabilities.
   *
   * @platform android
   */
  notificationButtons: NotificationButtonLayout | null
}

export interface AndroidUpdateOptions {
  /**
   * Whether the audio playback notification is also removed when the playback
   * stops. **If `stoppingAppPausesPlayback` is set to false, this will be
   * ignored.**
   */
  appKilledPlaybackBehavior?: AppKilledPlaybackBehavior

  /**
   * Whether to automatically skip silent audio segments during playback.
   * When enabled, the player will detect and skip over periods of silence.
   *
   * @default false
   */
  skipSilence?: boolean

  /**
   * Whether shuffle mode is enabled for queue playback.
   * When enabled, tracks will be played in random order.
   *
   * @default false
   */
  shuffle?: boolean

  /**
   * The rating type to use for ratings.
   * Determines how star ratings and thumbs up/down are handled.
   */
  ratingType?: RatingType

  /**
   * Slot-based button layout for Android notifications.
   * Provides explicit control over which buttons appear in which positions.
   *
   * When not specified, button layout is derived from capabilities.
   *
   * @platform android
   */
  notificationButtons?: NotificationButtonLayout | null
}

export interface NitroAndroidUpdateOptions {
  appKilledPlaybackBehavior?: AppKilledPlaybackBehavior
  skipSilence?: boolean
  shuffle?: boolean
  ratingType?: RatingType
  notificationButtons?: NotificationButtonLayout | null
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
 * // Disable specific capabilities
 * updateOptions({
 *   capabilities: { shuffleMode: false, repeatMode: false }
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
  android?: AndroidUpdateOptions

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
   * When null, progress events are disabled.
   * @default null
   */
  progressUpdateEventInterval?: number | null

  /**
   * Player capabilities to enable or disable.
   * All capabilities are enabled by default - only specify ones you want to disable.
   *
   * @example
   * ```typescript
   * // Disable shuffle and repeat
   * updateOptions({
   *   capabilities: {
   *     shuffleMode: false,
   *     repeatMode: false,
   *   }
   * })
   * ```
   */
  capabilities?: PlayerCapabilities

  /**
   * Supported playback rates for the playback-rate capability.
   * Used by CarPlay and lock screen rate controls.
   * @platform ios
   * @default [0.5, 1.0, 1.5, 2.0]
   */
  iosPlaybackRates?: number[]
}

export interface NativeUpdateOptions {
  /** Android-specific configuration options */
  android?: NitroAndroidUpdateOptions

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
   * When null, progress events are disabled.
   * @default null
   */
  progressUpdateEventInterval?: number | null

  capabilities?: PlayerCapabilities

  /**
   * Supported playback rates for the playback-rate capability.
   * @platform ios
   */
  iosPlaybackRates?: number[]
}

// MARK: - Functions

/**
 * Updates the configuration for the components.
 * Pass only the properties you want to change - all properties are optional.
 *
 * @param options - The partial options to update. Only changed properties need to be specified.
 * @see {@link getOptions} to get current options
 * @example
 * ```typescript
 * // Update single property
 * updateOptions({ repeatMode: 'track' });
 *
 * // Disable specific capabilities
 * updateOptions({
 *   capabilities: { shuffleMode: false },
 *   progressUpdateEventInterval: 0.5
 * });
 * ```
 */
export function updateOptions(options: UpdateOptions): void {
  nativeBrowser.updateOptions(options)
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
  return nativeBrowser.getOptions() as Options
}

// MARK: - Event Callbacks

/**
 * Subscribes to player options changes.
 * @param callback - Called when the player options change
 * @returns Cleanup function to unsubscribe
 */
export const onOptionsChanged = NativeUpdatedValue.emitterize<Options>(
  (cb) => (nativeBrowser.onOptionsChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current player options and updates when they change.
 * @returns The current player options
 */
export function useOptions(): Options {
  return useNativeUpdatedValue(getOptions, onOptionsChanged)
}
