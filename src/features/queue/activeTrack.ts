import type { Track } from '../../types'
import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeValueRefreshedBy } from '../../utils/useNativeValueRefreshedBy'
import { onFavoriteChanged } from '../favorites'

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

// MARK: - Getters

/**
 * Gets the active track or undefined if there is no current track.
 */
export function getActiveTrack(): Track | undefined {
  return nativeBrowser.getActiveTrack() ?? undefined
}

/**
 * Gets the index of the active track in the queue or undefined if there is no
 * current track.
 */
export function getActiveTrackIndex(): number | undefined {
  return nativeBrowser.getActiveTrackIndex() ?? undefined
}

// MARK: - Event Callbacks

/**
 * Subscribes to active track change events.
 *
 * Fires on **transitions only** — selection, queue advance, skip. In-place
 * metadata mutations of the active track (a favorite toggle) emit
 * {@link onFavoriteChanged} instead; {@link useActiveTrack} subscribes to
 * both, so UI bound through the hook still re-renders on a heart toggle.
 *
 * @param callback - Called when the active track changes
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onActiveTrackChanged =
  NativeUpdatedValue.emitterize<PlaybackActiveTrackChangedEvent>(
    (cb) => (nativeBrowser.onPlaybackActiveTrackChanged = cb)
  )

// MARK: - Hooks

// Module-level so the hook's subscription identity is stable across renders.
const activeTrackInvalidators = [onActiveTrackChanged, onFavoriteChanged]

/**
 * Hook that returns the current active track, updating on transitions AND on
 * in-place mutations of the active track (favorite toggles).
 * @returns The current active track or undefined
 */
export function useActiveTrack(): Track | undefined {
  return useNativeValueRefreshedBy(getActiveTrack, activeTrackInvalidators)
}
