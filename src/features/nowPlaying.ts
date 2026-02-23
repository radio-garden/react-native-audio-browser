import type { NowPlayingMetadata, NowPlayingUpdate } from './metadata'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Functions

/**
 * Updates the now playing notification metadata.
 * Pass null/undefined to clear overrides and revert to track metadata.
 *
 * @example
 * ```ts
 * // Override notification with stream metadata
 * updateNowPlaying({
 *   title: streamMetadata.title,
 *   artist: streamMetadata.artist,
 * })
 *
 * // Clear overrides, revert to track metadata
 * updateNowPlaying(null)
 * ```
 */
export function updateNowPlaying(update: NowPlayingUpdate | null): void {
  nativeBrowser.updateNowPlaying(update ?? undefined)
}

/**
 * Gets the current now playing metadata (override if set, else track metadata).
 */
export function getNowPlaying(): NowPlayingMetadata | undefined {
  return nativeBrowser.getNowPlaying() ?? undefined
}

// MARK: - Event Callbacks

/**
 * Subscribes to now playing metadata change events.
 * Fires when updateNowPlaying is called or when the track changes.
 * @param callback - Called when now playing metadata changes
 * @returns Cleanup function to unsubscribe
 */
export const onNowPlayingChanged =
  NativeUpdatedValue.emitterize<NowPlayingMetadata>(
    (cb) => (nativeBrowser.onNowPlayingChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current now playing metadata and updates when it changes.
 * @returns The current now playing metadata or undefined
 */
export function useNowPlaying(): NowPlayingMetadata | undefined {
  return useNativeUpdatedValue(getNowPlaying, onNowPlayingChanged)
}
