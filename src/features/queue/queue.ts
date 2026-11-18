import type { Track } from '../../types'
import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeValueRefreshedBy } from '../../utils/useNativeValueRefreshedBy'
import { onFavoriteChanged } from '../favorites'

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
 * @throws If `insertBeforeIndex` is negative or past the queue's length.
 * Android and the web treat `-1` as append; iOS throws on it.
 */
export function add(tracks: Track[], insertBeforeIndex?: number): void
/**
 * Adds a track to the queue.
 * @param track - The track to add to the queue.
 * @param insertBeforeIndex - (Optional) The index to insert the track before.
 * By default the track will be added to the end of the queue.
 * @throws If `insertBeforeIndex` is negative or past the queue's length.
 * Android and the web treat `-1` as append; iOS throws on it.
 */
export function add(track: Track, insertBeforeIndex?: number): void
export function add(tracks: Track | Track[], insertBeforeIndex?: number): void {
  const addTracks = Array.isArray(tracks) ? tracks : [tracks]
  if (addTracks.length > 0) {
    nativeBrowser.add(addTracks, insertBeforeIndex)
  }
}

/**
 * Replaces the current track or loads the track as the first in the queue.
 * @param track - The track to load.
 * @throws If called before `setupPlayer`.
 */
export function load(track: Track): void {
  nativeBrowser.load(track)
}

/**
 * Move a track within the queue.
 * @param fromIndex - The index of the track to be moved.
 * @param toIndex - The index to move the track to. On iOS and Android an index
 * past the end moves the track to the end of the queue; the web throws.
 * @throws If `fromIndex` is outside the queue or `toIndex` is negative (on the
 * web, also if `toIndex` is past the last index).
 */
export function move(fromIndex: number, toIndex: number): void {
  nativeBrowser.move(fromIndex, toIndex)
}

/**
 * Removes multiple tracks from the queue by their indexes.
 *
 * If the active track is removed, the track after it becomes active. On iOS
 * and the web that is the next index, wrapping to the first track. On Android
 * it is the next track in playback order (shuffle and repeat apply), and
 * removing the last track with repeat `off` makes the first track active but
 * ends playback.
 *
 * @param indexes - The indexes of the tracks to be removed.
 * @throws If an index is outside the queue. Android checks every index before
 * removing any, and also throws if an index appears twice. iOS removes from the
 * highest index down, so an invalid index can throw after some tracks are gone,
 * and a repeated index also removes the track that moved into its place. The
 * web skips invalid and repeated indexes without throwing.
 */
export function remove(indexes: number[]): void
/**
 * Removes a track from the queue by its index.
 *
 * If the active track is removed, the track after it becomes active. On iOS
 * and the web that is the next index, wrapping to the first track. On Android
 * it is the next track in playback order (shuffle and repeat apply), and
 * removing the last track with repeat `off` makes the first track active but
 * ends playback.
 *
 * @param index - The index of the track to be removed.
 * @throws If `index` is outside the queue, on iOS and Android; the web
 * ignores it.
 */
export function remove(index: number): void
export function remove(indexOrIndexes: number | number[]): void {
  nativeBrowser.remove(
    Array.isArray(indexOrIndexes) ? indexOrIndexes : [indexOrIndexes]
  )
}

/**
 * Removes every track after the active track's index in the queue. With
 * shuffle on, this is queue order, not the shuffled play order.
 */
export function removeUpcomingTracks(): void {
  nativeBrowser.removeUpcomingTracks()
}

/**
 * Skips to a track in the queue.
 * @param index - The index of the track to skip to.
 * @param initialPosition - (Optional) The initial position to seek to in seconds.
 * @throws If `index` is outside the queue.
 */
export function skip(index: number, initialPosition?: number): void {
  nativeBrowser.skip(index, initialPosition)
}

/**
 * Skips to the next track in the queue.
 * @param initialPosition - (Optional) The position in seconds to start the next
 * track from. On iOS and Android, with no next track the active track seeks to
 * it instead; on the web the call does nothing.
 */
export function skipToNext(initialPosition?: number): void {
  nativeBrowser.skipToNext(initialPosition)
}

/**
 * Skips to the previous track in the queue.
 * @param initialPosition - (Optional) The position in seconds to start the
 * previous track from. On iOS and Android, with no previous track the active
 * track seeks to it instead; on the web the call does nothing.
 */
export function skipToPrevious(initialPosition?: number): void {
  nativeBrowser.skipToPrevious(initialPosition)
}

/**
 * Sets (replaces) the queue.
 *
 * On iOS and Android this does not change the play/pause state: the player
 * keeps playing if it was playing and stays paused if it was paused. On the
 * web it stops playback. Call `play` after it to start playback.
 *
 * @param tracks - The tracks to set as the queue.
 * @param startIndex - (Optional) The index of the track to make active.
 * Defaults to 0 on iOS and Android; on the web, omitting it leaves no track
 * active. iOS clamps an out-of-range index into the queue, Android throws, and
 * the web ignores it.
 * @param startPosition - (Optional) The position in seconds to start the active track from.
 */
export function setQueue(
  tracks: Track[],
  startIndex?: number,
  startPosition?: number
): void {
  nativeBrowser.setQueue(tracks, startIndex, startPosition)
}

// MARK: - Getters

/**
 * Gets a track object from the queue.
 * @param index - The index of the track.
 * @returns The track object or undefined if there isn't a track object at that index.
 */
export function getTrack(index: number): Track | undefined {
  return nativeBrowser.getTrack(index) as unknown as Track
}

/**
 * Gets the whole queue.
 */
export function getQueue(): Track[] {
  return nativeBrowser.getQueue() as unknown as Track[]
}

/**
 * Gets the queue's version: it changes when the queue's tracks or their order
 * change, whoever changed them (a `setQueue`, a pick in CarPlay or Android
 * Auto, `add`, `remove`, `move`), and never repeats while the app runs. Equal
 * readings mean the queue is the same, without copying it across the bridge.
 */
export function getQueueVersion(): number {
  return nativeBrowser.getQueueVersion()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback queue ended events: fires when the last track in
 * playback order finishes with repeat mode `off` and the playback state
 * becomes `ended`. Never fires under `track` or `queue` repeat.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onQueueEnded =
  NativeUpdatedValue.emitterize<PlaybackQueueEndedEvent>(
    (cb) => (nativeBrowser.onPlaybackQueueEnded = cb)
  )

/**
 * Subscribes to queue change events.
 * Called when tracks are added, removed, or reordered. On iOS and Android a
 * favorite toggle also fires it, alongside `onFavoriteChanged`. On web a
 * favorite toggle fires only `onFavoriteChanged`. {@link useQueue} subscribes
 * to both.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onQueueChanged = NativeUpdatedValue.emitterize<Track[]>(
  (cb) => (nativeBrowser.onPlaybackQueueChanged = cb)
)

// MARK: - Hooks

// Module-level so the hook's subscription identity is stable across renders.
const queueInvalidators = [onQueueChanged, onFavoriteChanged]

/**
 * Hook that returns the current queue, updating on queue changes AND on
 * in-place mutations of queued tracks (favorite toggles).
 * @returns The current queue
 */
export function useQueue(): Track[] {
  return useNativeValueRefreshedBy(getQueue, queueInvalidators)
}
