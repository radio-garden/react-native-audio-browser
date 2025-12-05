import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'
import type { PlaybackError } from '../errors'

// MARK: - Types

/**
 * PlaybackState options:
 * - `'none'`: Indicates that the player is idle (initial state, or no track
 *   loaded)
 * - `'ready'`: Indicates that the player has loaded a track and is ready to
 *   play (but paused)
 * - `'playing'`: Indicates that the player is currently playing
 * - `'paused'`: Indicates that the player is currently paused
 * - `'stopped'`: Indicates that the player is currently stopped
 * - `'loading'`: Indicates that the initial load of the item is occurring.
 * - `'buffering'`: Indicates that the player is currently loading more data
 *   before it can continue playing or is ready to start playing.
 * - `'error'`: Indicates that playback of the current item failed. Call
 *   `AudioBrowser.getError()` to get more information on the type of error that
 *   occurred. Call `AudioBrowser.retry()` or `AudioBrowser.play()` to try to play
 *   the item again.
 * - `'ended'`: Indicates that playback stopped due to the end of the queue
 *   being reached.
 */
export type PlaybackState =
  | 'none'
  | 'ready'
  | 'playing'
  | 'paused'
  | 'stopped'
  | 'loading'
  | 'buffering'
  | 'error'
  | 'ended'

export type Playback = {
  state: PlaybackState
  error?: PlaybackError
}

// MARK: - Getters

/**
 * Gets the playback state and error (if any) of the player.
 * @see {@link PlaybackState} for possible state values
 */
export function getPlayback(): Playback {
  return nativeBrowser.getPlayback()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback state changes.
 * @param callback - Called when the playback state changes
 * @returns Cleanup function to unsubscribe
 */
export const onPlaybackChanged = NativeUpdatedValue.emitterize<Playback>(
  (cb) => (nativeBrowser.onPlaybackChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current playback state and updates when it changes.
 * @returns The current playback state
 */
export function usePlayback(): Playback {
  return useNativeUpdatedValue(getPlayback, onPlaybackChanged)
}
