import { nativePlayer } from '../native'
import { LazyEmitter } from '../utils/LazyEmitter'
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue'

// MARK: - Types

export type PlayingState = {
  playing: boolean
  buffering: boolean
}

// MARK: - Getters

/**
 * Gets the playing state (playing and buffering flags).
 */
export function getPlayingState(): PlayingState {
  return nativePlayer.getPlayingState()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playing state changes.
 * @param callback - Called when playing or buffering state changes
 * @returns Cleanup function to unsubscribe
 */
export const onPlayingState = LazyEmitter.emitterize<PlayingState>(
  (cb) => (nativePlayer.onPlaybackPlayingState = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current playing state and updates when it changes.
 * @returns The current playing state (playing and buffering flags)
 */
export function usePlayingState(): PlayingState {
  return useUpdatedNativeValue(getPlayingState, onPlayingState)
}
