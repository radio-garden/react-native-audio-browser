import { useCallback } from 'react';

import { AudioBrowser as TrackPlayer } from '../NativeAudioBrowser';
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue';
;

/**
 * Event data for when playWhenReady changes.
 */
export interface PlaybackPlayWhenReadyChangedEvent {
  /** Whether the player will play when ready */
  playWhenReady: boolean;
}

// MARK: - Getters

/**
 * Gets whether the player will play automatically when it is ready to do so.
 */
export function getPlayWhenReady(): boolean {
  return TrackPlayer.getPlayWhenReady();
}

// MARK: - Setters

/**
 * Sets whether the player will play automatically when it is ready to do so.
 * This is the equivalent of calling `play()` when `playWhenReady = true`
 * or `pause()` when `playWhenReady = false`.
 */
export function setPlayWhenReady(playWhenReady: boolean): void {
  TrackPlayer.setPlayWhenReady(playWhenReady);
}

// MARK: - Event Callbacks

/**
 * Subscribes to play when ready changes.
 * @param callback - Called when playWhenReady changes
 * @returns Cleanup function to unsubscribe
 */
export function onPlayWhenReadyChanged(
  callback: (event: PlaybackPlayWhenReadyChangedEvent) => void,
): () => void {
  return TrackPlayer.onPlaybackPlayWhenReadyChanged(callback as () => void)
    .remove;
}

// MARK: - Hooks

/**
 * Hook that returns the current playWhenReady state and updates when it changes.
 * @returns The current playWhenReady state
 */
export function usePlayWhenReady(): boolean {
  const subscribe = useCallback(
    (callback: (event: PlaybackPlayWhenReadyChangedEvent) => void) =>
      onPlayWhenReadyChanged(callback),
    [],
  );

  return useUpdatedNativeValue(getPlayWhenReady, subscribe, 'playWhenReady');
}
