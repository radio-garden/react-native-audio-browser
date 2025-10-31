import { AudioBrowser as TrackPlayer } from '../NativeAudioBrowser';
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue';
import type { PlaybackError } from './errors';

// MARK: - Types

/**
 * State options:
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
 *   `TrackPlayer.getError()` to get more information on the type of error that
 *   occurred. Call `TrackPlayer.retry()` or `TrackPlayer.play()` to try to play
 *   the item again.
 * - `'ended'`: Indicates that playback stopped due to the end of the queue
 *   being reached.
 */
export type State =
  | 'none'
  | 'ready'
  | 'playing'
  | 'paused'
  | 'stopped'
  | 'loading'
  | 'buffering'
  | 'error'
  | 'ended'

export type PlaybackState = {
  state: State
  error?: PlaybackError
}

// MARK: - Getters

/**
 * Gets the playback state of the player.
 * @see https://rntp.dev/docs/api/constants/state
 */
export function getPlaybackState(): PlaybackState {
  return TrackPlayer.getPlaybackState()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback state changes.
 * @param callback - Called when the playback state changes
 * @returns Cleanup function to unsubscribe
 */
export function onPlaybackState(
  callback: (state: PlaybackState) => void
): () => void {
  return TrackPlayer.onPlaybackState(callback as () => void).remove
}

// MARK: - Hooks

/**
 * Hook that returns the current playback state and updates when it changes.
 * @returns The current playback state
 */
export function usePlaybackState(): PlaybackState {
  return useUpdatedNativeValue(getPlaybackState, onPlaybackState)
}
