import { nativePlayer } from '../native'

// MARK: - Playback Controls

/**
 * Resets the player stopping the current track and clearing the queue.
 */
export function reset(): void {
  nativePlayer.reset()
}

/**
 * Plays or resumes the current track.
 */
export function play(): void {
  nativePlayer.play()
}

/**
 * Pauses the current track.
 */
export function pause(): void {
  nativePlayer.pause()
}

/**
 * Toggles playback between play and pause.
 */
export function togglePlayback(): void {
  nativePlayer.togglePlayback()
}

/**
 * Stops the current track.
 */
export function stop(): void {
  nativePlayer.stop()
}

/**
 * Retries the current item when the playback state is `State.Error`.
 */
export function retry(): void {
  nativePlayer.retry()
}

/**
 * Seeks to a specified time position in the current track.
 * @param position - The position to seek to in seconds.
 */
export function seekTo(position: number): void {
  nativePlayer.seekTo(position)
}

/**
 * Seeks by a relative time offset in the current track.
 * @param offset - The time offset to seek by in seconds.
 */
export function seekBy(offset: number): void {
  nativePlayer.seekBy(offset)
}
