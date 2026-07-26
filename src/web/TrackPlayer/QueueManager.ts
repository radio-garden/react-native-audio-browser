import type { RepeatMode as RepeatModeType } from '../../features'
import type { Track } from '../../types'
import { assertedNotNullish } from '../../utils/validation'
import { fisherYatesShuffle } from '../util/shuffle'
import { RepeatMode } from './RepeatMode'

/**
 * What removing tracks means for the currently-playing item — the caller (the
 * player) acts on it (reload / stop), keeping engine concerns out of the queue.
 */
export type RemoveOutcome =
  | { kind: 'no-current' } // nothing was playing; the queue was filtered, nothing else to do
  | { kind: 'kept' } // the current track survived; its index was adjusted in place
  | { kind: 'reload'; index: number } // the current track was removed; reload this index
  | { kind: 'emptied' } // the current track was removed and the queue is now empty

/**
 * Owns the play queue: the track list, the current/last index, repeat mode and
 * shuffle order, plus all the pure index math (next/previous, wrap-around,
 * shuffle ordering, index adjustment on mutation). It has no knowledge of the
 * playback engine — the web analog of iOS's `QueueManager` + `ShuffleOrder`.
 * Orchestration (loading a track when the index changes) stays with the caller.
 */
export class QueueManager {
  private _tracks: Track[] = []
  private _currentIndex: number | undefined
  private _lastIndex: number | undefined
  private _repeatMode: RepeatModeType = RepeatMode.Off
  private _shuffleEnabled = false
  private shuffleOrder: number[] = []

  // MARK: tracks

  get tracks(): Track[] {
    // Fresh copy: mutations happen in place, so handing out the live array
    // defeats React's reference-equality change detection in consumers of
    // onPlaybackQueueChanged/getQueue — and lets callers corrupt the queue.
    return [...this._tracks]
  }

  get length(): number {
    return this._tracks.length
  }

  getTrack(index: number): Track | undefined {
    return this._tracks[index]
  }

  /** Replaces the whole queue and resets the current/last index. */
  setTracks(tracks: Track[]): void {
    this._tracks = tracks
    this._currentIndex = undefined
    this._lastIndex = undefined
  }

  /** Replaces a single track in place (e.g. a favorite toggle). */
  replaceTrack(index: number, track: Track): void {
    this._tracks[index] = track
  }

  // MARK: current index

  get currentIndex(): number | undefined {
    return this._currentIndex
  }

  set currentIndex(index: number | undefined) {
    this._lastIndex = this._currentIndex
    this._currentIndex = index
  }

  get lastIndex(): number | undefined {
    return this._lastIndex
  }

  // MARK: repeat / shuffle

  get repeatMode(): RepeatModeType {
    return this._repeatMode
  }

  set repeatMode(mode: RepeatModeType) {
    this._repeatMode = mode
  }

  get shuffleEnabled(): boolean {
    return this._shuffleEnabled
  }

  setShuffleEnabled(enabled: boolean): void {
    this._shuffleEnabled = enabled
    if (enabled) this.regenerateShuffleOrder()
  }

  regenerateShuffleOrder(): void {
    this.shuffleOrder = Array.from({ length: this._tracks.length }, (_, i) => i)
    fisherYatesShuffle(this.shuffleOrder)

    // Keep the current track at the front so navigation continues from it.
    if (this._currentIndex === undefined) return
    const currentPos = this.shuffleOrder.indexOf(this._currentIndex)
    if (currentPos > 0) {
      const first = assertedNotNullish(this.shuffleOrder[0])
      this.shuffleOrder[0] = assertedNotNullish(this.shuffleOrder[currentPos])
      this.shuffleOrder[currentPos] = first
    }
  }

  // MARK: navigation

  nextIndex(): number | undefined {
    if (this._currentIndex === undefined) return undefined

    if (!this._shuffleEnabled) {
      const next = this._currentIndex + 1
      return next >= this._tracks.length ? undefined : next
    }

    const pos = this.shuffleOrder.indexOf(this._currentIndex)
    if (pos === -1) return undefined
    const nextPos = pos + 1
    return nextPos >= this.shuffleOrder.length
      ? undefined
      : this.shuffleOrder[nextPos]
  }

  previousIndex(): number | undefined {
    if (this._currentIndex === undefined) return undefined

    if (!this._shuffleEnabled) {
      const prev = this._currentIndex - 1
      return prev < 0 ? undefined : prev
    }

    const pos = this.shuffleOrder.indexOf(this._currentIndex)
    if (pos === -1) return undefined
    const prevPos = pos - 1
    return prevPos < 0 ? undefined : this.shuffleOrder[prevPos]
  }

  wrapAroundFirstIndex(): number | undefined {
    if (this._repeatMode !== RepeatMode.Queue) return undefined
    return this._shuffleEnabled ? this.shuffleOrder[0] : 0
  }

  wrapAroundLastIndex(): number | undefined {
    if (this._repeatMode !== RepeatMode.Queue) return undefined
    return this._shuffleEnabled
      ? this.shuffleOrder[this.shuffleOrder.length - 1]
      : this._tracks.length - 1
  }

  // MARK: mutations

  /** Inserts tracks at `insertBeforeIndex`, or appends when omitted/-1. */
  insert(tracks: Track[], insertBeforeIndex?: number): void {
    if (insertBeforeIndex !== -1 && insertBeforeIndex !== undefined) {
      this._tracks.splice(insertBeforeIndex, 0, ...tracks)
      // Keep the pointer on the same track (remove()/move() already do).
      if (
        this._currentIndex !== undefined &&
        insertBeforeIndex <= this._currentIndex
      ) {
        this._currentIndex += tracks.length
      }
    } else {
      this._tracks.push(...tracks)
    }
  }

  /**
   * Removes the tracks at `indexes`, adjusting the current index. Returns what
   * the caller must do for the current track (the queue itself never reloads).
   */
  remove(indexes: number[]): RemoveOutcome {
    const idxSet = new Set(indexes)
    const current = this._currentIndex
    let removedBeforeCurrent = 0
    let removedCurrent = false

    this._tracks = this._tracks.filter((_track, idx) => {
      if (!idxSet.has(idx)) return true
      if (idx === current) removedCurrent = true
      else if (current !== undefined && idx < current) removedBeforeCurrent++
      return false
    })

    if (current === undefined) return { kind: 'no-current' }

    if (!removedCurrent) {
      this._currentIndex = current - removedBeforeCurrent
      return { kind: 'kept' }
    }

    if (this._tracks.length === 0) {
      this._currentIndex = undefined
      return { kind: 'emptied' }
    }

    // Reset so the caller's reload always loads the track at this position.
    this._currentIndex = undefined
    const adjusted = current - removedBeforeCurrent
    return { kind: 'reload', index: Math.min(adjusted, this._tracks.length - 1) }
  }

  move(fromIndex: number, toIndex: number): void {
    const item = this._tracks[fromIndex]
    if (item === undefined) throw new Error('index out of bounds')

    this._tracks.splice(fromIndex, 1)
    this._tracks.splice(toIndex, 0, item)

    // Track the current item's new position (matches Android's moveMediaItem).
    const current = this._currentIndex
    if (current !== undefined) {
      if (fromIndex === current) this._currentIndex = toIndex
      else if (fromIndex < current && toIndex >= current) {
        this._currentIndex = current - 1
      } else if (fromIndex > current && toIndex <= current) {
        this._currentIndex = current + 1
      }
    }

    if (this._shuffleEnabled) this.regenerateShuffleOrder()
  }

  /** Drops every track after the current one. */
  removeUpcoming(): void {
    if (this._currentIndex === undefined) return
    this._tracks = this._tracks.slice(0, this._currentIndex + 1)
    if (this._shuffleEnabled) this.regenerateShuffleOrder()
  }

  /** Empties the queue and resets the index. */
  clear(): void {
    this._tracks = []
    this._currentIndex = undefined
    this._lastIndex = undefined
  }
}
