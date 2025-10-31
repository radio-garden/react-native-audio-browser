import { AudioBrowser as TrackPlayer } from '../NativeAudioBrowser';
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue';
;

export type PlaybackError = {
  code: string;
  message: string;
};

/**
 * Emitted when a playback error occurs.
 */
export interface PlaybackErrorEvent {
  error?: PlaybackError;
}

// MARK: - Getters

/**
 * Gets the current playback error, if any.
 * @returns The current error or undefined if there is no error
 */
export function getPlaybackError(): PlaybackError | undefined {
  const error = TrackPlayer.getPlaybackError();
  if (!error) return undefined;
  return error as PlaybackError;
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback error events.
 * @param callback - Called when a playback error occurs
 * @returns Cleanup function to unsubscribe
 */
export function onPlaybackError(
  callback: (event: PlaybackErrorEvent) => void,
): () => void {
  return TrackPlayer.onPlaybackError(callback as () => void).remove;
}

// MARK: - Hooks

/**
 * Hook that returns the current playback error and updates when it changes.
 * @returns The current playback error or undefined
 */
export function usePlaybackError(): PlaybackError | undefined {
  return useUpdatedNativeValue(getPlaybackError, onPlaybackError, 'error');
}
