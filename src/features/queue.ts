import { nativePlayer } from '../native'
import type { Track } from '../types'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Types

/**
 * Event data for when the playback queue has ended.
 */
export interface PlaybackQueueEndedEvent {
  /** The index of the active track when the playback queue ended. */
  track: number
  /** The playback position in seconds of the active track when the playback queue ended. */
  position: number
}

// MARK: - Queue Management

/**
 * Adds one or more tracks to the queue.
 * @param tracks - The tracks to add to the queue.
 * @param insertBeforeIndex - (Optional) The index to insert the tracks before.
 * By default the tracks will be added to the end of the queue.
 */
export function add(tracks: Track[], insertBeforeIndex?: number): void
/**
 * Adds a track to the queue.
 * @param track - The track to add to the queue.
 * @param insertBeforeIndex - (Optional) The index to insert the track before.
 * By default the track will be added to the end of the queue.
 */
export function add(track: Track, insertBeforeIndex?: number): void
export function add(tracks: Track | Track[], insertBeforeIndex = -1): void {
  const addTracks = Array.isArray(tracks) ? tracks : [tracks]
  if (addTracks.length > 0) {
    nativePlayer.add(addTracks, insertBeforeIndex)
  }
}

/**
 * Replaces the current track or loads the track as the first in the queue.
 * @param track - The track to load.
 */
export function load(track: Track): void {
  nativePlayer.load(track)
}

/**
 * Move a track within the queue.
 * @param fromIndex - The index of the track to be moved.
 * @param toIndex - The index to move the track to. If the index is larger than
 * the size of the queue, then the track is moved to the end of the queue.
 */
export function move(fromIndex: number, toIndex: number): void {
  nativePlayer.move(fromIndex, toIndex)
}

/**
 * Removes multiple tracks from the queue by their indexes.
 *
 * If the current track is removed, the next track will activated. If the
 * current track was the last track in the queue, the first track will be
 * activated.
 *
 * @param indexes - The indexes of the tracks to be removed.
 */
export function remove(indexes: number[]): void
/**
 * Removes a track from the queue by its index.
 *
 * If the current track is removed, the next track will activated. If the
 * current track was the last track in the queue, the first track will be
 * activated.
 *
 * @param index - The index of the track to be removed.
 */
export function remove(index: number): void
export function remove(indexOrIndexes: number | number[]): void {
  nativePlayer.remove(
    Array.isArray(indexOrIndexes) ? indexOrIndexes : [indexOrIndexes]
  )
}

/**
 * Clears any upcoming tracks from the queue.
 */
export function removeUpcomingTracks(): void {
  nativePlayer.removeUpcomingTracks()
}

/**
 * Skips to a track in the queue.
 * @param index - The index of the track to skip to.
 * @param initialPosition - (Optional) The initial position to seek to in seconds.
 */
export function skip(index: number, initialPosition?: number): void {
  nativePlayer.skip(index, initialPosition)
}

/**
 * Skips to the next track in the queue.
 * @param initialPosition - (Optional) The initial position to seek to in seconds.
 */
export function skipToNext(initialPosition?: number): void {
  nativePlayer.skipToNext(initialPosition)
}

/**
 * Skips to the previous track in the queue.
 * @param initialPosition - (Optional) The initial position to seek to in seconds.
 */
export function skipToPrevious(initialPosition?: number): void {
  nativePlayer.skipToPrevious(initialPosition)
}

/**
 * Sets the queue.
 * @param tracks - The tracks to set as the queue.
 */
export function setQueue(tracks: Track[]): void {
  nativePlayer.setQueue(tracks)
}

// MARK: - Getters

/**
 * Gets a track object from the queue.
 * @param index - The index of the track.
 * @returns The track object or undefined if there isn't a track object at that index.
 */
export function getTrack(index: number): Track | undefined {
  return nativePlayer.getTrack(index) as unknown as Track
}

/**
 * Gets the whole queue.
 */
export function getQueue(): Track[] {
  return nativePlayer.getQueue() as unknown as Track[]
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback queue ended events.
 * @param callback - Called when playback has paused due to reaching the end of the queue
 * @returns Cleanup function to unsubscribe
 */
export const onQueueEnded = NativeUpdatedValue.emitterize<PlaybackQueueEndedEvent>(
  (cb) => (nativePlayer.onPlaybackQueueEnded = cb)
)

/**
 * Subscribes to queue change events.
 * Called when tracks are added, removed, reordered, or when track metadata changes.
 * @param callback - Called with the updated queue
 * @returns Cleanup function to unsubscribe
 */
export const onQueueChanged = NativeUpdatedValue.emitterize<Track[]>(
  (cb) => (nativePlayer.onPlaybackQueueChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current queue and updates when it changes.
 * @returns The current queue
 */
export function useQueue(): Track[] {
  return useNativeUpdatedValue(getQueue, onQueueChanged)
}
