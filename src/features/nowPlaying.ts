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

/**
 * Temporarily replaces now-playing fields for `durationMs`, then reverts to
 * the live metadata. The flash outranks the now-playing formatter and the
 * `updateNowPlaying` override while active (live metadata can't stomp it),
 * and the revert runs on a native timer — JS timers pause in a backgrounded
 * host (the lock screen case) — so it fires reliably. A track change clears
 * the flash early; repeated calls restart the window.
 *
 * The feedback channel for refused remote commands: external surfaces have
 * no toast primitive, so a transient metadata swap is the only way to talk
 * to the user there.
 *
 * @example
 * ```ts
 * // A radio product with an hourly skip allowance:
 * handleRemoteNext(() => {
 *   if (skipsRemaining() > 0) skipToNext()
 *   else flashNowPlaying({ artist: 'Skip limit reached' }, 3000)
 * })
 * ```
 */
export function flashNowPlaying(
  update: NowPlayingUpdate,
  durationMs: number
): void {
  nativeBrowser.flashNowPlaying(update, durationMs)
}

/**
 * Clears an active flash immediately, reverting to the live metadata.
 * No-op when no flash is active.
 */
export function clearNowPlayingFlash(): void {
  nativeBrowser.clearNowPlayingFlash()
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
