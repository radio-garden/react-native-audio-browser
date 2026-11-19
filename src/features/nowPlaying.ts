import type { NowPlayingMetadata, NowPlayingUpdate } from './metadata'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Functions

/**
 * Updates the now playing notification metadata.
 *
 * Lasts until you pass `null`, which clears it, or the active track changes. A `nowPlaying`
 * formatter's fields win over it. With `nowPlaying: false` the iOS and
 * Android surface ignores it, though `getNowPlaying` still reports it; web
 * ignores `nowPlaying`.
 *
 * @example
 * ```ts
 * // Override notification with stream metadata
 * updateNowPlaying({
 *   title: streamMetadata.title,
 *   artist: streamMetadata.artist,
 * })
 *
 * // Clear the override
 * updateNowPlaying(null)
 * ```
 */
export function updateNowPlaying(update: NowPlayingUpdate | null): void {
  nativeBrowser.updateNowPlaying(update ?? undefined)
}

/**
 * Returns what the now-playing surface shows: flash, formatter output,
 * override or track fields, in that priority. `undefined` when there is no
 * active track. With `nowPlaying: false`, it returns the flash, override or
 * track fields, regardless of what the surface shows.
 */
export function getNowPlaying(): NowPlayingMetadata | undefined {
  return nativeBrowser.getNowPlaying() ?? undefined
}

/**
 * Temporarily replaces now-playing fields for `durationMs`, then reverts to
 * the live metadata. While active it outranks the `nowPlaying` formatter
 * ({@link features/player!FormatNowPlayingCallback | FormatNowPlayingCallback}) and the `updateNowPlaying` override.
 * The revert runs on a native timer, so it fires while the app is
 * backgrounded. A track change clears the flash early; repeated calls
 * restart the window.
 *
 * On web the flash is a plain `updateNowPlaying` override on a JS timer. It
 * replaces any override you set, the revert clears the override instead of
 * restoring it, and a track change does not cancel the timer, so the revert
 * can also clear an override set for the next track. With
 * `nowPlaying: false`, Android ignores the flash; iOS still shows it.
 *
 * See the [Now playing guide](https://audiobrowser.dev/guide/now-playing#the-flash-transient-top-priority).
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
 * No-op when no flash is active. On web it also clears the
 * `updateNowPlaying` override.
 */
export function clearNowPlayingFlash(): void {
  nativeBrowser.clearNowPlayingFlash()
}

// MARK: - Event Callbacks

/**
 * Subscribes to now playing metadata change events.
 * Fires when the now-playing lines change: track change, `updateNowPlaying`,
 * a flash starting or ending, or a new formatter result. On iOS and
 * Android, an update that leaves the lines unchanged does not fire; on web
 * every `updateNowPlaying` call, flash start and revert fires.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
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
