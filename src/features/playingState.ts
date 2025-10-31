import { AudioBrowser as TrackPlayer } from '../NativeAudioBrowser';
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue';
;

// MARK: - Types

export type PlayingState = {
  playing: boolean;
  buffering: boolean;
};

// MARK: - Getters

/**
 * Gets the playing state (playing and buffering flags).
 */
export function getPlayingState(): PlayingState {
  return TrackPlayer.getPlayingState() as PlayingState;
}

// MARK: - Event Callbacks

/**
 * Subscribes to playing state changes.
 * @param callback - Called when playing or buffering state changes
 * @returns Cleanup function to unsubscribe
 */
export function onPlayingState(
  callback: (state: PlayingState) => void,
): () => void {
  return TrackPlayer.onPlaybackPlayingState(callback as () => void).remove;
}

// MARK: - Hooks

/**
 * Hook that returns the current playing state and updates when it changes.
 * @returns The current playing state (playing and buffering flags)
 */
export function usePlayingState(): PlayingState {
  return useUpdatedNativeValue(getPlayingState, onPlayingState);
}
