import { nativePlayer } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

export type PlaybackError = {
  code: string
  message: string
}

/**
 * Emitted when a playback error occurs.
 */
export interface PlaybackErrorEvent {
  error?: PlaybackError
}

// MARK: - Getters

/**
 * Gets the current playback error, if any.
 * @returns The current error or undefined if there is no error
 */
export function getPlaybackError(): PlaybackError | undefined {
  const error = nativePlayer.getPlaybackError()
  return error ?? undefined
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback error events.
 * @param callback - Called when a playback error occurs
 * @returns Cleanup function to unsubscribe
 */
export const onPlaybackError = NativeUpdatedValue.emitterize<PlaybackErrorEvent>(
  (cb) => (nativePlayer.onPlaybackError = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current playback error and updates when it changes.
 * @returns The current playback error or undefined
 */
export function usePlaybackError(): PlaybackError | undefined {
  return useNativeUpdatedValue(getPlaybackError, onPlaybackError, 'error')
}
