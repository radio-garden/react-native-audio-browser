import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'

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
  return nativeBrowser.getPlayingState()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playing state changes.
 * @param callback - Called when playing or buffering state changes
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onPlayingState = NativeUpdatedValue.emitterize<PlayingState>(
  (cb) => (nativeBrowser.onPlaybackPlayingState = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current playing state and updates when it changes.
 * @returns The current playing state (playing and buffering flags)
 */
export function usePlayingState(): PlayingState {
  return useNativeUpdatedValue(getPlayingState, onPlayingState)
}
