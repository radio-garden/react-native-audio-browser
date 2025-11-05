import { nativeAudioBrowser } from '../NativeAudioBrowser'
import type { Track } from '../types'
import { LazyEmitter } from '../utils/LazyEmitter'
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue'

/**
 * Event data for the active track.
 */
export interface PlaybackActiveTrackEvent {
  /** The active track index or undefined if there is no active track. */
  index?: number
  /** The active track or undefined if there is no active track. */
  track?: Track
}

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
  return nativeAudioBrowser.getActiveTrack() ?? undefined
}

/**
 * Gets the index of the active track in the queue or undefined if there is no
 * current track.
 */
export function getActiveTrackIndex(): number | undefined {
  return nativeAudioBrowser.getActiveTrackIndex() ?? undefined
}

// MARK: - Event Callbacks

/**
 * Subscribes to active track change events.
 * @param callback - Called when the active track changes
 * @returns Cleanup function to unsubscribe
 */
export const onActiveTrackChanged =
  LazyEmitter.emitterize<PlaybackActiveTrackChangedEvent>(
    (cb) => (nativeAudioBrowser.onPlaybackActiveTrackChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current active track and updates when it changes.
 * @returns The current active track or undefined
 */
export function useActiveTrack(): Track | undefined {
  return useUpdatedNativeValue(getActiveTrack, onActiveTrackChanged, 'track')
}
