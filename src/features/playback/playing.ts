import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'

// MARK: - Types

/** Whether the player is playing, and whether it is buffering while it does. */
export type PlayingState = {
  /**
   * True while the player intends to play and can: `playWhenReady` is set and
   * the state is not `'none'`, `'stopped'`, `'ended'` or `'error'`. Also true
   * while loading or buffering, so it does not mean sound is audible. On
   * Android, also false while a transient audio-focus loss holds output back,
   * though `playWhenReady` stays true.
   */
  playing: boolean
  /**
   * True while the player intends to play and the state is `'loading'` or
   * `'buffering'`. On Android, also false while a transient audio-focus loss
   * holds output back, though `playWhenReady` stays true.
   */
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
 * Emits the {@link PlayingState} each time `playing` or `buffering` changes.
 * Subscribe with `onPlayingState.addListener(cb)`, which returns an
 * unsubscribe function.
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
