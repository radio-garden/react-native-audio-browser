// MARK: - Playback Controls

import { nativeBrowser } from '../../native'

/**
 * Resets the player stopping the current track and clearing the queue.
 */
export function reset(): void {
  nativeBrowser.reset()
}

/**
 * Plays or resumes the current track.
 */
export function play(): void {
  nativeBrowser.play()
}

/**
 * Pauses the current track.
 */
export function pause(): void {
  nativeBrowser.pause()
}

/**
 * Toggles playback between play and pause.
 */
export function togglePlayback(): void {
  nativeBrowser.togglePlayback()
}

/**
 * Stops playback. The track stays current, and play() starts it again.
 * iOS rewinds a non-live track to the start; Android and web keep the
 * position, so play() resumes where it stopped.
 */
export function stop(): void {
  nativeBrowser.stop()
}

/**
 * Retries playing the current item. Meant for the `'error'` state, but not
 * gated on it: on iOS it reloads the current item in any state, even while
 * playing. On Android and web it reloads whenever the player is stopped or
 * failed, without setting the play intent, so after stop() it loads the item
 * but does not play it.
 *
 * @see {@link PlaybackState}
 * @see {@link usePlayback}
 */
export function retry(): void {
  nativeBrowser.retry()
}

/**
 * Seeks to a specified time position in the current track.
 * @param position - The position to seek to in seconds.
 */
export function seekTo(position: number): void {
  nativeBrowser.seekTo(position)
}

/**
 * Jump to the live edge of the current track; no-op unless the track
 * declares `live: true`. A stream with a seekable live window (HLS) seeks
 * to the window's edge; a non-seekable stream (e.g. ICY radio) reconnects
 * to rejoin live. iOS re-runs `media.resolve` on the reconnect; Android
 * reuses the resolved URL and re-resolves only after it fails to open.
 */
export function seekToLiveEdge(): void {
  nativeBrowser.seekToLiveEdge()
}

/**
 * Seeks by a relative time offset in the current track.
 * @param offset - The time offset to seek by in seconds.
 */
export function seekBy(offset: number): void {
  nativeBrowser.seekBy(offset)
}
