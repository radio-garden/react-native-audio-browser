import type { FavoriteConfig } from '../../types'
import type { CarPlayNowPlayingButton } from '../../types/browser'
import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'

// MARK: - Types

/**
 * Player capabilities control which media controls are available to the user.
 * Most capabilities are enabled by default - only specify the ones you want to change.
 * Exceptions that default to off: `jumpForward`, `jumpBackward`, and `favorite`.
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
   * Enable track favoriting.
   *
   * Turns on the favorite/like heart across all surfaces:
   * - iOS: Control Center + CarPlay now-playing.
   * - Android: notification button slot + Android Auto now-playing, and an
   *   (empty or filled) heart on playable browse rows.
   *
   * `match` controls how the ids from `setFavorites` are
   * compared against a track's `src` to decide its `favorited` state
   * (see `FavoritesMatchMode`); `true` is shorthand for `'exact'`.
   *
   * - `false` / omitted: favoriting off everywhere.
   * - `true`: on, with `'exact'` id matching.
   * - `{ match }`: on, with the given match mode.
   *
   * @example
   * ```ts
   * // 'exact': a favorite id must equal the track's src.
   * favorite: { match: 'exact' }
   * setFavorites(['https://cdn.example.com/audio/track-42.mp3'])
   * // → favorited when src === 'https://cdn.example.com/audio/track-42.mp3'
   *
   * // 'partial': a favorite id matches when it is a full path segment of src.
   * favorite: { match: 'partial' }
   * setFavorites(['track-42'])
   * // → favorited when src is '/library/track-42' or '/stream/track-42?hq=1',
   * //   but NOT '/library/track-420'
   * ```
   *
   * @default false
   */
  favorite?: boolean | FavoriteConfig
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
 * console.log(options.forwardJumpInterval); // 15
 * console.log(options.capabilities); // { shuffleMode: false } - only disabled caps shown
 * console.log(options.android?.skipSilence); // true (Android only)
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
   * Most capabilities are enabled by default - this shows which ones are disabled.
   */
  capabilities: PlayerCapabilities

  /** iOS-specific player options with resolved defaults (only present on iOS). */
  ios?: IOSOptions
}

/**
 * iOS-specific player options with resolved defaults (from {@link getOptions}).
 * Only present on iOS.
 */
export interface IOSOptions {
  /**
   * Supported playback rates for the playback-rate capability.
   * Used by CarPlay and lock screen rate controls.
   * @default [0.5, 1.0, 1.5, 2.0]
   */
  playbackRates: number[]

  /**
   * Whether the "Up Next" button is enabled on the CarPlay Now Playing screen.
   * @default true
   */
  carPlayUpNextButton: boolean

  /**
   * Buttons shown on the CarPlay Now Playing screen (left-to-right, up to 5).
   * @default []
   */
  carPlayNowPlayingButtons: CarPlayNowPlayingButton[]
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
  notificationButtons?: NotificationButtonLayout | null
}

/**
 * iOS-specific player options that can be changed at runtime via {@link updateOptions}.
 * @platform ios
 */
export interface IOSUpdateOptions {
  /**
   * Supported playback rates for the playback-rate capability.
   * Used by CarPlay and lock screen rate controls.
   * @default [0.5, 1.0, 1.5, 2.0]
   */
  playbackRates?: number[]

  /**
   * Enable the "Up Next" button on the CarPlay Now Playing screen. The button is
   * automatically hidden when the queue has only one track.
   * @default true
   */
  carPlayUpNextButton?: boolean

  /**
   * Configure up to 5 buttons on the CarPlay Now Playing screen, arranged in
   * array order (left to right).
   *
   * @example
   * ```typescript
   * updateOptions({ ios: { carPlayNowPlayingButtons: ['repeat'] } })
   * ```
   * @default []
   */
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]
}

export interface NitroIOSUpdateOptions {
  playbackRates?: number[]
  carPlayUpNextButton?: boolean
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]
}

/**
 * Partial options for updating player configuration.
 * Only specify the properties you want to change - all properties are optional.
 * Use null to reset properties to their defaults.
 *
 * @example
 * ```typescript
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
 *   android: { skipSilence: true }
 * });
 * ```
 */
export interface UpdateOptions {
  /** Android-specific configuration options */
  android?: AndroidUpdateOptions

  /** iOS-specific configuration options */
  ios?: IOSUpdateOptions

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
}

export interface NativeUpdateOptions {
  /** Android-specific configuration options */
  android?: NitroAndroidUpdateOptions

  /** iOS-specific configuration options */
  ios?: NitroIOSUpdateOptions

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
}

// MARK: - Functions

const MAX_CARPLAY_NOW_PLAYING_BUTTONS = 5

/**
 * Warns when more CarPlay now-playing buttons are configured than CarPlay renders.
 * Shared by {@link updateOptions} and `setupPlayer` (both can carry `ios` options).
 */
export function validateIOSUpdateOptions(ios?: IOSUpdateOptions): void {
  const buttons = ios?.carPlayNowPlayingButtons
  if (buttons && buttons.length > MAX_CARPLAY_NOW_PLAYING_BUTTONS) {
    console.warn(
      `[react-native-audio-browser] ${buttons.length} CarPlay now-playing ` +
        `buttons configured; CarPlay shows at most ${MAX_CARPLAY_NOW_PLAYING_BUTTONS}.`
    )
  }
}

/**
 * Updates the configuration for the components.
 * Pass only the properties you want to change - all properties are optional.
 *
 * @param options - The partial options to update. Only changed properties need to be specified.
 * @see {@link getOptions} to get current options
 * @example
 * ```typescript
 * // Disable specific capabilities
 * updateOptions({
 *   capabilities: { shuffleMode: false },
 *   progressUpdateEventInterval: 0.5
 * });
 * ```
 */
export function updateOptions(options: UpdateOptions): void {
  validateIOSUpdateOptions(options.ios)
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
 * if (options.capabilities.shuffleMode === false) {
 *   // Shuffle is disabled
 * }
 * ```
 */
export function getOptions(): Options {
  return nativeBrowser.getOptions()
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
