import { useCallback } from 'react'

import { nativePlayer } from '../native'
import { LazyEmitter } from '../utils/LazyEmitter'
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue'

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
  return nativePlayer.getPlayWhenReady()
}

// MARK: - Setters

/**
 * Sets whether the player will play automatically when it is ready to do so.
 * This is the equivalent of calling `play()` when `playWhenReady = true`
 * or `pause()` when `playWhenReady = false`.
 */
export function setPlayWhenReady(playWhenReady: boolean): void {
  nativePlayer.setPlayWhenReady(playWhenReady)
}

// MARK: - Event Callbacks

/**
 * Subscribes to play when ready changes.
 * @param callback - Called when playWhenReady changes
 * @returns Cleanup function to unsubscribe
 */
export const onPlayWhenReadyChanged =
  LazyEmitter.emitterize<PlaybackPlayWhenReadyChangedEvent>(
    (cb) => (nativePlayer.onPlaybackPlayWhenReadyChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current playWhenReady state and updates when it changes.
 * @returns The current playWhenReady state
 */
export function usePlayWhenReady(): boolean {
  const subscribe = useCallback(
    (callback: (event: PlaybackPlayWhenReadyChangedEvent) => void) =>
      onPlayWhenReadyChanged(callback),
    []
  )

  return useUpdatedNativeValue(getPlayWhenReady, subscribe, 'playWhenReady')
}
