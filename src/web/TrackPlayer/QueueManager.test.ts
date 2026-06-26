import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Track } from '../../types'
import { QueueManager } from './QueueManager'
import { RepeatMode } from './RepeatMode'

// Make shuffle deterministic (identity) so the shuffle-order navigation path is
// assertable: shuffleOrder becomes [0,1,2,…] before the current-first swap.
vi.mock('../util/shuffle', () => ({
  fisherYatesShuffle: () => {}
}))

const tracks = (n: number): Track[] =>
  Array.from({ length: n }, (_, i) => ({ src: `s${i}` }) as Track)

describe('QueueManager', () => {
  let q: QueueManager

  beforeEach(() => {
    q = new QueueManager()
  })

  describe('current index', () => {
    it('tracks the previous index as lastIndex on set', () => {
      q.currentIndex = 2
      expect(q.lastIndex).toBeUndefined()
      q.currentIndex = 5
      expect(q.lastIndex).toBe(2)
      expect(q.currentIndex).toBe(5)
    })
  })

  describe('sequential navigation', () => {
    beforeEach(() => {
      q.setTracks(tracks(3))
      q.currentIndex = 0
    })

    it('advances and stops at the end (no wrap by default)', () => {
      expect(q.nextIndex()).toBe(1)
      q.currentIndex = 2
      expect(q.nextIndex()).toBeUndefined()
    })

    it('goes back and stops at the start', () => {
      q.currentIndex = 1
      expect(q.previousIndex()).toBe(0)
      q.currentIndex = 0
      expect(q.previousIndex()).toBeUndefined()
    })

    it('returns undefined navigation when there is no current track', () => {
      const empty = new QueueManager()
      empty.setTracks(tracks(3))
      expect(empty.nextIndex()).toBeUndefined()
      expect(empty.previousIndex()).toBeUndefined()
    })
  })

  describe('wrap-around (repeat playlist)', () => {
    beforeEach(() => {
      q.setTracks(tracks(3))
      q.currentIndex = 2
    })

    it('only wraps when repeat mode is playlist', () => {
      expect(q.wrapAroundFirstIndex()).toBeUndefined()
      expect(q.wrapAroundLastIndex()).toBeUndefined()
      q.repeatMode = RepeatMode.Playlist
      expect(q.wrapAroundFirstIndex()).toBe(0)
      expect(q.wrapAroundLastIndex()).toBe(2)
    })
  })

  describe('shuffle navigation', () => {
    beforeEach(() => {
      q.setTracks(tracks(4))
      q.currentIndex = 0
      q.setShuffleEnabled(true) // identity shuffle → order [0,1,2,3]
    })

    it('navigates along the shuffle order with the current track first', () => {
      // current (0) is already at position 0, so order stays [0,1,2,3]
      expect(q.nextIndex()).toBe(1)
      q.currentIndex = 3
      expect(q.nextIndex()).toBeUndefined()
      expect(q.previousIndex()).toBe(2)
    })

    it('moves the current track to the front of the shuffle order on regen', () => {
      q.currentIndex = 2
      q.regenerateShuffleOrder()
      // order was [0,1,2,3]; current (2) swapped to front → [2,1,0,3]
      expect(q.previousIndex()).toBeUndefined()
      expect(q.nextIndex()).toBe(1)
    })
  })

  describe('insert', () => {
    it('appends when no insert index is given', () => {
      q.setTracks(tracks(2))
      q.insert(tracks(1))
      expect(q.length).toBe(3)
    })

    it('splices at the given index', () => {
      q.setTracks([{ src: 'a' }, { src: 'b' }] as Track[])
      q.insert([{ src: 'x' }] as Track[], 1)
      expect(q.tracks.map((t) => t.src)).toEqual(['a', 'x', 'b'])
    })
  })

  describe('remove', () => {
    beforeEach(() => {
      q.setTracks(tracks(5)) // s0..s4
      q.currentIndex = 2
    })

    it('keeps the current track, shifting its index down for removals before it', () => {
      const result = q.remove([0])
      expect(result).toEqual({ kind: 'kept' })
      expect(q.currentIndex).toBe(1)
    })

    it('reports the index to reload when the current track is removed', () => {
      const result = q.remove([2])
      // index clamped into the shrunken queue; current reset so caller reloads
      expect(result).toEqual({ kind: 'reload', index: 2 })
      expect(q.currentIndex).toBeUndefined()
    })

    it('reports emptied when the last remaining track is removed', () => {
      q.setTracks(tracks(1))
      q.currentIndex = 0
      expect(q.remove([0])).toEqual({ kind: 'emptied' })
    })

    it('reports no-current when nothing is playing', () => {
      const idle = new QueueManager()
      idle.setTracks(tracks(3))
      expect(idle.remove([0])).toEqual({ kind: 'no-current' })
      expect(idle.length).toBe(2) // filter still applied
    })
  })

  describe('move', () => {
    beforeEach(() => {
      q.setTracks(tracks(4)) // s0..s3
    })

    it('follows the current track to its new position', () => {
      q.currentIndex = 1
      q.move(1, 3)
      expect(q.tracks.map((t) => t.src)).toEqual(['s0', 's2', 's3', 's1'])
      expect(q.currentIndex).toBe(3)
    })

    it('shifts the current index when a track moves across it', () => {
      q.currentIndex = 2
      q.move(0, 3) // moving an earlier track past current shifts current down
      expect(q.currentIndex).toBe(1)
    })

    it('throws on an out-of-bounds source index', () => {
      expect(() => q.move(9, 0)).toThrow('index out of bounds')
    })
  })

  describe('removeUpcoming', () => {
    it('drops everything after the current track', () => {
      q.setTracks(tracks(5))
      q.currentIndex = 1
      q.removeUpcoming()
      expect(q.tracks.map((t) => t.src)).toEqual(['s0', 's1'])
    })
  })

  describe('clear', () => {
    it('empties the queue and resets the index', () => {
      q.setTracks(tracks(3))
      q.currentIndex = 1
      q.clear()
      expect(q.length).toBe(0)
      expect(q.currentIndex).toBeUndefined()
    })
  })
})
