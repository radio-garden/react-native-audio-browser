import TrackPlayer from '../NativeTrackPlayer';
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue';

// MARK: - Types

/**
 * RepeatMode options:
 * - `'off'`: Playback stops when the last track in the queue has finished
 *   playing.
 * - `'track'`: Repeats the current track infinitely during ongoing playback.
 * - `'queue'`: Repeats the entire queue infinitely.
 */
export type RepeatMode = 'off' | 'track' | 'queue';

// MARK: - Getters

/**
 * Gets the current repeat mode.
 */
export function getRepeatMode(): RepeatMode {
  return TrackPlayer.getRepeatMode() as RepeatMode;
}

/**
 * Sets the repeat mode.
 * @param repeatMode - The repeat mode to set
 */
export function setRepeatMode(repeatMode: RepeatMode) {
  TrackPlayer.setRepeatMode(repeatMode);
}

// MARK: - Event Callbacks

export interface RepeatModeChangedEvent {
  /** The new repeat mode */
  repeatMode: RepeatMode;
}

/**
 * Subscribes to repeat mode changes.
 * @param callback - Called when repeat mode changes
 * @returns Cleanup function to unsubscribe
 */
export function onRepeatModeChanged(
  callback: (event: RepeatModeChangedEvent) => void,
): () => void {
  return TrackPlayer.onPlaybackRepeatModeChanged(callback as () => void).remove;
}

// MARK: - Hooks

/**
 * Hook that returns the current repeat mode and updates when it changes.
 * @returns The current repeat mode
 */
export function useRepeatMode(): RepeatMode {
  return useUpdatedNativeValue(
    getRepeatMode,
    onRepeatModeChanged,
    'repeatMode',
  );
}
