import { nativePlayer } from '../native'
import type { Track } from '../types'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

/**
 * Event data for when the active track changes.
 */
export interface PlaybackActiveTrackChangedEvent {
  /** The index of previously active track. */
  lastIndex?: number
  /** The previously active track or undefined when there wasn't a previously active track. */
  lastTrack?: Track
  /** The position of the previously active track in seconds. */
  lastPosition: number
  /** The newly active track index or undefined if there is no longer an active track. */
  index?: number
  /** The newly active track or undefined if there is no longer an active track. */
  track?: Track
}

/**
 * Event data for when the favorite state of the active track changes.
 * Emitted when the user taps the heart button in a media controller (notification, Android Auto, CarPlay).
 */
export interface FavoriteChangedEvent {
  /** The track whose favorite state changed. */
  track: Track
  /** The new favorite state. */
  favorited: boolean
}

// MARK: - Getters

/**
 * Gets the active track or undefined if there is no current track.
 */
export function getActiveTrack(): Track | undefined {
  return nativePlayer.getActiveTrack() ?? undefined
}

/**
 * Gets the index of the active track in the queue or undefined if there is no
 * current track.
 */
export function getActiveTrackIndex(): number | undefined {
  return nativePlayer.getActiveTrackIndex() ?? undefined
}

// MARK: - Setters

/**
 * Sets the favorited state of the currently playing track.
 * Updates the heart icon in media controllers (notification, Android Auto).
 *
 * Use this for programmatic favorite changes (e.g., from a favorite button in your app).
 * For heart button taps from media controllers, use `onFavoriteChanged` instead -
 * the native side handles those automatically.
 *
 * @param favorited - Whether the track is favorited
 *
 * @example
 * ```ts
 * setActiveTrackFavorited(true)
 * ```
 */
export function setActiveTrackFavorited(favorited: boolean): void {
  nativePlayer.setActiveTrackFavorited(favorited)
}

// MARK: - Event Callbacks

/**
 * Subscribes to active track change events.
 * @param callback - Called when the active track changes
 * @returns Cleanup function to unsubscribe
 */
export const onActiveTrackChanged =
  NativeUpdatedValue.emitterize<PlaybackActiveTrackChangedEvent>(
    (cb) => (nativePlayer.onPlaybackActiveTrackChanged = cb)
  )

/**
 * Subscribes to favorite state change events.
 * Called when the user taps the heart button in a media controller.
 * The native side has already updated the track's favorite state and UI.
 *
 * @param callback - Called with the track and its new favorite state
 * @returns Cleanup function to unsubscribe
 *
 * @example
 * ```ts
 * const unsubscribe = onFavoriteChanged.addListener(({ track, favorited }) => {
 *   // Persist the change to your backend/storage
 *   if (favorited) {
 *     addToFavorites(track)
 *   } else {
 *     removeFromFavorites(track)
 *   }
 * })
 * ```
 */
export const onFavoriteChanged =
  NativeUpdatedValue.emitterize<FavoriteChangedEvent>(
    (cb) => (nativePlayer.onFavoriteChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current active track and updates when it changes.
 * @returns The current active track or undefined
 */
export function useActiveTrack(): Track | undefined {
  return useNativeUpdatedValue(getActiveTrack, onActiveTrackChanged, 'track')
}
