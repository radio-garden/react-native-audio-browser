
// MARK: - Playback Controls

import { nativeBrowser } from "../../native"

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
 * Stops the current track.
 */
export function stop(): void {
  nativeBrowser.stop()
}

/**
 * Retries playing the current item when the playback state is `'error'`.
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
 * Seeks by a relative time offset in the current track.
 * @param offset - The time offset to seek by in seconds.
 */
export function seekBy(offset: number): void {
  nativeBrowser.seekBy(offset)
}
