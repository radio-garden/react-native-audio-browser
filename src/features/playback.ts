import { AudioBrowser as TrackPlayer } from '../NativeAudioBrowser'

// MARK: - Playback Controls

/**
 * Resets the player stopping the current track and clearing the queue.
 */
export function reset(): void {
  TrackPlayer.reset()
}

/**
 * Plays or resumes the current track.
 */
export function play(): void {
  TrackPlayer.play()
}

/**
 * Pauses the current track.
 */
export function pause(): void {
  TrackPlayer.pause()
}

/**
 * Toggles playback between play and pause.
 */
export function togglePlayback(): void {
  TrackPlayer.togglePlayback()
}

/**
 * Stops the current track.
 */
export function stop(): void {
  TrackPlayer.stop()
}

/**
 * Retries the current item when the playback state is `State.Error`.
 */
export function retry(): void {
  TrackPlayer.retry()
}

/**
 * Seeks to a specified time position in the current track.
 * @param position - The position to seek to in seconds.
 */
export function seekTo(position: number): void {
  TrackPlayer.seekTo(position)
}

/**
 * Seeks by a relative time offset in the current track.
 * @param offset - The time offset to seek by in seconds.
 */
export function seekBy(offset: number): void {
  TrackPlayer.seekBy(offset)
}
