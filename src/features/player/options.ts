import type { CarPlayNowPlayingButton } from '../../types/browser'
import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'
import { validateIOSUpdateOptions } from './validateOptions'

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
   * - Android: a place in the {@link RemoteButtonLayout} — the notification,
   *   Android Auto and the Android 13+ system media controls — and an
   *   (empty or filled) heart on playable browse rows.
   *
   * The ids passed to `setFavorites` are matched exactly against a track's
   * *identity* — its `id` when set, falling back to `src`. Store favorites as
   * the same stable identifier you put in `Track.id` (or as the full `src`
   * for tracks without an id).
   *
   * @example
   * ```ts
   * favorite: true
   * setFavorites(['track-42'])
   * // → favorited when track.id === 'track-42'
   * //   (or, for id-less tracks, when track.src === 'track-42')
   * ```
   *
   * @default false
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
 * Buttons that can be placed in the Android button layout — the interactive
 * controls a listener taps in the notification, on the Android Auto Now Playing
 * screen, and in the Android 13+ system media controls.
 */
export type RemoteButton =
  | 'skip-to-previous'
  | 'skip-to-next'
  | 'jump-backward'
  | 'jump-forward'
  | 'favorite'

/**
 * Where each button sits on Android.
 *
 * Android offers exactly three positions, and play/pause always occupies the
 * centre — you cannot move it, and you cannot put two buttons on the same side
 * of it:
 *
 * ```
 *   back  │  ▶ play/pause  │  forward        overflow ⋯
 * ```
 *
 * - **`back`** — the single position left of play/pause
 * - **`forward`** — the single position right of play/pause
 * - **`overflow`** — everything else, in priority order
 *
 * **A layout describes the whole arrangement.** All three fields are required —
 * there is no per-field merge with the capability defaults, so what you write is
 * exactly what renders. Use `undefined` for an empty position and `[]` for no
 * overflow. To go back to the derived defaults, omit `remoteButtonLayout`
 * entirely (or set it to `null`); that is the only switch.
 *
 * **Overflow order is priority, not coordinates.** Each surface renders as many
 * buttons as it has room for, taking them from the front — a phone
 * notification, the Android 13+ system media controls, and a car head unit all
 * have different budgets. A head unit with a spare slot may promote the first
 * overflow entry onto the main row, and a long list gets truncated by whichever
 * surface is showing it. Put what matters most first.
 *
 * Placement never changes what a control *can* do: a button left out entirely
 * still responds to a Bluetooth remote or headset, as long as its
 * {@link PlayerCapabilities} entry is enabled.
 *
 * @example
 * ```typescript
 * // Podcast-style: jump either side of play/pause
 * remoteButtonLayout: {
 *   back: 'jump-backward',
 *   forward: 'jump-forward',
 *   overflow: ['skip-to-previous', 'skip-to-next', 'favorite']
 * }
 *
 * // Music-style: skip either side of play/pause
 * remoteButtonLayout: {
 *   back: 'skip-to-previous',
 *   forward: 'skip-to-next',
 *   overflow: ['favorite']
 * }
 *
 * // Live radio: forward only, nothing to the left
 * remoteButtonLayout: {
 *   back: undefined,
 *   forward: 'skip-to-next',
 *   overflow: []
 * }
 * ```
 */
export type RemoteButtonLayout = {
  /** The single position left of play/pause. `undefined` leaves it empty. */
  back: RemoteButton | undefined
  /** The single position right of play/pause. `undefined` leaves it empty. */
  forward: RemoteButton | undefined
  /**
   * Everything else, most important first — surfaces promote and truncate from
   * the front. `[]` for none.
   */
  overflow: RemoteButton[]
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
   *
   * On Android, an interval of exactly 5, 10, 15 or 30 seconds gets an icon
   * showing that number; any other interval gets an icon without one. Other
   * values are perfectly valid — the interval also sets the seek distance.
   *
   * @default 15
   */
  forwardJumpInterval: number

  /**
   * Jump backward interval in seconds when using jump backward controls.
   *
   * On Android, an interval of exactly 5, 10, 15 or 30 seconds gets an icon
   * showing that number; any other interval gets an icon without one. Other
   * values are perfectly valid — the interval also sets the seek distance.
   *
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
   * Ordered button layout, applied to the notification, Android Auto, and the
   * Android 13+ system media controls. See {@link RemoteButtonLayout}.
   *
   * When not specified, the layout is derived from capabilities.
   *
   * @platform android
   */
  remoteButtonLayout: RemoteButtonLayout | null
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
   * Ordered button layout, applied to the notification, Android Auto, and the
   * Android 13+ system media controls. See {@link RemoteButtonLayout}.
   *
   * When not specified, the layout is derived from capabilities.
   *
   * @platform android
   */
  remoteButtonLayout?: RemoteButtonLayout | null
}

/**
 * Wire shape of {@link AndroidUpdateOptions} — what crosses the Nitro bridge.
 * @internal
 */
export interface NitroAndroidUpdateOptions {
  appKilledPlaybackBehavior?: AppKilledPlaybackBehavior
  skipSilence?: boolean
  remoteButtonLayout?: RemoteButtonLayout | null
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

/**
 * Wire shape of {@link IOSUpdateOptions} — what crosses the Nitro bridge.
 * @internal
 */
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
   *
   * On Android, an interval of exactly 5, 10, 15 or 30 seconds gets an icon
   * showing that number; any other interval gets an icon without one. Other
   * values are perfectly valid — the interval also sets the seek distance.
   *
   * @default 15
   */
  forwardJumpInterval?: number

  /**
   * Jump backward interval in seconds when using jump backward controls.
   *
   * On Android, an interval of exactly 5, 10, 15 or 30 seconds gets an icon
   * showing that number; any other interval gets an icon without one. Other
   * values are perfectly valid — the interval also sets the seek distance.
   *
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

/**
 * Wire shape of {@link UpdateOptions} — what crosses the Nitro bridge.
 * @internal
 */
export interface NativeUpdateOptions {
  /** Android-specific configuration options */
  android?: NitroAndroidUpdateOptions

  /** iOS-specific configuration options */
  ios?: NitroIOSUpdateOptions

  /**
   * Jump forward interval in seconds when using jump forward controls.
   *
   * On Android, an interval of exactly 5, 10, 15 or 30 seconds gets an icon
   * showing that number; any other interval gets an icon without one. Other
   * values are perfectly valid — the interval also sets the seek distance.
   *
   * @default 15
   */
  forwardJumpInterval?: number

  /**
   * Jump backward interval in seconds when using jump backward controls.
   *
   * On Android, an interval of exactly 5, 10, 15 or 30 seconds gets an icon
   * showing that number; any other interval gets an icon without one. Other
   * values are perfectly valid — the interval also sets the seek distance.
   *
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
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
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
