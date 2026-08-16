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
    return this._tracks
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
    if (this._shuffleEnabled) this.regenerateShuffleOrder()
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
    const hadNoCurrent = this._currentIndex === undefined
    this._lastIndex = this._currentIndex
    this._currentIndex = index

    // A queue with no current track has an order that was shuffled with nothing
    // to lead it — `setTracks` clears the index before regenerating, and
    // `insert` drops new indices at random positions — so the track that then
    // becomes current can sit anywhere, including last. That reads as
    // end-of-queue immediately: `nextIndex` is undefined from the first track,
    // so auto-advance fires onQueueEnded after one track. Re-pin at the moment
    // the queue gains a current track. Safe as a setter hook here because the
    // tracks are always in place before the index is assigned (both `setTracks`
    // and `insert` run first).
    if (hadNoCurrent && index !== undefined && this._shuffleEnabled) {
      this.regenerateShuffleOrder()
    }
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

  // The shuffle order is maintained incrementally across mutations (matching
  // iOS's ShuffleOrder and ExoPlayer's DefaultShuffleOrder) rather than
  // regenerated: a regeneration re-randomizes the upcoming order and erases
  // the played history, leaving previous() dead after any add/remove/move.

  /** Shifts indices for an insertion and slots the new ones in at random positions. */
  private shuffleOrderInsert(at: number, count: number): void {
    if (!this._shuffleEnabled || count <= 0) return
    this.shuffleOrder = this.shuffleOrder.map((idx) =>
      idx >= at ? idx + count : idx
    )
    for (let i = 0; i < count; i++) {
      const pos = Math.floor(Math.random() * (this.shuffleOrder.length + 1))
      this.shuffleOrder.splice(pos, 0, at + i)
    }
  }

  /** Drops removed indices and shifts the survivors down past them. */
  private shuffleOrderRemove(removed: Set<number>): void {
    if (!this._shuffleEnabled || removed.size === 0) return
    const sorted = [...removed].sort((a, b) => a - b)
    this.shuffleOrder = this.shuffleOrder
      .filter((idx) => !removed.has(idx))
      .map((idx) => idx - sorted.filter((r) => r < idx).length)
  }

  /** Remaps indices for a move, keeping every track's shuffle position. */
  private shuffleOrderMove(fromIndex: number, toIndex: number): void {
    if (!this._shuffleEnabled) return
    this.shuffleOrder = this.shuffleOrder.map((idx) => {
      if (idx === fromIndex) return toIndex
      let adjusted = idx > fromIndex ? idx - 1 : idx
      if (adjusted >= toIndex) adjusted += 1
      return adjusted
    })
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
      // Match Android/iOS, which throw here: a negative index other than the
      // -1 sentinel would splice from the end and mis-shift the current
      // pointer; past-the-end would silently append under a lying index.
      if (insertBeforeIndex < 0 || insertBeforeIndex > this._tracks.length) {
        throw new Error(
          `insertBeforeIndex out of bounds: ${insertBeforeIndex} (use -1 to append)`
        )
      }
      this._tracks.splice(insertBeforeIndex, 0, ...tracks)
      // Keep the pointer on the same track (remove()/move() already do).
      if (
        this._currentIndex !== undefined &&
        insertBeforeIndex <= this._currentIndex
      ) {
        this._currentIndex += tracks.length
      }
      this.shuffleOrderInsert(insertBeforeIndex, tracks.length)
    } else {
      this._tracks.push(...tracks)
      this.shuffleOrderInsert(
        this._tracks.length - tracks.length,
        tracks.length
      )
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
    this.shuffleOrderRemove(idxSet)

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
    // Wrap: removing the last-and-current track activates the first (the
    // documented contract, matching iOS's modulo).
    return { kind: 'reload', index: adjusted % this._tracks.length }
  }

  move(fromIndex: number, toIndex: number): void {
    // Both ends are checked: an out-of-range destination would write a
    // permanent out-of-range entry into the incrementally-maintained order.
    const item = this._tracks[fromIndex]
    if (item === undefined || toIndex < 0 || toIndex >= this._tracks.length) {
      throw new Error('index out of bounds')
    }

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

    this.shuffleOrderMove(fromIndex, toIndex)
  }

  /** Drops every track after the current one. */
  removeUpcoming(): void {
    if (this._currentIndex === undefined) return
    const dropped = new Set<number>()
    for (let i = this._currentIndex + 1; i < this._tracks.length; i++) {
      dropped.add(i)
    }
    this._tracks = this._tracks.slice(0, this._currentIndex + 1)
    this.shuffleOrderRemove(dropped)
  }

  /** Empties the queue and resets the index. */
  clear(): void {
    this._tracks = []
    this._currentIndex = undefined
    this._lastIndex = undefined
    // Mutations maintain the order incrementally, so a stale order would
    // survive the clear and corrupt the first post-clear insert.
    this.shuffleOrder = []
  }
}
