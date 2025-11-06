import { nativePlayer } from '../native'
import { LazyEmitter } from '../utils/LazyEmitter'
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue'

// MARK: - Types

/**
 * RepeatMode options:
 * - `'off'`: Playback stops when the last track in the queue has finished
 *   playing.
 * - `'track'`: Repeats the current track infinitely during ongoing playback.
 * - `'queue'`: Repeats the entire queue infinitely.
 */
export type RepeatMode = 'off' | 'track' | 'queue'

// MARK: - Getters

/**
 * Gets the current repeat mode.
 */
export function getRepeatMode(): RepeatMode {
  return nativePlayer.getRepeatMode()
}

/**
 * Sets the repeat mode.
 * @param repeatMode - The repeat mode to set
 */
export function setRepeatMode(repeatMode: RepeatMode) {
  nativePlayer.setRepeatMode(repeatMode)
}

// MARK: - Event Callbacks

export interface RepeatModeChangedEvent {
  /** The new repeat mode */
  repeatMode: RepeatMode
}

/**
 * Subscribes to repeat mode changes.
 * @param callback - Called when repeat mode changes
 * @returns Cleanup function to unsubscribe
 */
export const onRepeatModeChanged =
  LazyEmitter.emitterize<RepeatModeChangedEvent>(
    (cb) => (nativePlayer.onPlaybackRepeatModeChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current repeat mode and updates when it changes.
 * @returns The current repeat mode
 */
export function useRepeatMode(): RepeatMode {
  return useUpdatedNativeValue(getRepeatMode, onRepeatModeChanged, 'repeatMode')
}
