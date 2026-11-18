import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'

/**
 * Event data for when playWhenReady changes.
 */
export interface PlaybackPlayWhenReadyChangedEvent {
  /** Whether the player will play when ready */
  playWhenReady: boolean
}

// MARK: - Getters

/**
 * Gets whether the player will play automatically when it is ready to do so.
 */
export function getPlayWhenReady(): boolean {
  return nativeBrowser.getPlayWhenReady()
}

// MARK: - Setters

/**
 * Sets whether the player will play automatically when it is ready to do so.
 * Before setupPlayer resolves, the value is kept and applied at setup, while
 * play() is dropped on iOS and Android. On Android, `true` does not restart a
 * stopped or failed player; call play() or retry() for that.
 */
export function setPlayWhenReady(playWhenReady: boolean): void {
  nativeBrowser.setPlayWhenReady(playWhenReady)
}

// MARK: - Event Callbacks

/**
 * Emits each time `playWhenReady` changes. Subscribe with
 * `onPlayWhenReadyChanged.addListener(cb)`, which returns an unsubscribe
 * function.
 */
export const onPlayWhenReadyChanged =
  NativeUpdatedValue.emitterize<PlaybackPlayWhenReadyChangedEvent>(
    (cb) => (nativeBrowser.onPlaybackPlayWhenReadyChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current playWhenReady state and updates when it changes.
 * @returns The current playWhenReady state
 */
export function usePlayWhenReady(): boolean {
  return useNativeUpdatedValue(
    getPlayWhenReady,
    onPlayWhenReadyChanged,
    'playWhenReady'
  )
}
