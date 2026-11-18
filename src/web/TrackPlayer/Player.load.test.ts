import { describe, expect, it, vi } from 'vitest'
import type { Playback } from '../../features'
import type { Track } from '../../types'
import { Player } from './Player'

// A Shaka load interrupted by stop() or a newer load() rejects; only the
// active load may surface that rejection — otherwise the .catch clobbers the
// Stopped state (or tears down the newer load via onError's unload).
class LoadTestPlayer extends Player {
  rejecters: Array<(err: unknown) => void> = []
  resolvers: Array<() => void> = []
  unloadCalls = 0

  constructor() {
    super()
    this.element = {
      play: () => Promise.resolve(),
      pause: () => {}
    } as unknown as HTMLMediaElement
    this.player = {
      load: () =>
        new Promise<void>((resolve, reject) => {
          this.resolvers.push(resolve)
          this.rejecters.push(reject)
        }),
      unload: () => {
        this.unloadCalls++
        return Promise.resolve()
      }
    } as unknown as typeof this.player
  }

  read(): Playback {
    return this.state
  }

  rejectLoad(index: number, err: unknown): void {
    this.rejecters[index]?.(err)
  }
}

const track = (n: number): Track =>
  ({
    id: `t${n}`,
    src: `https://example.com/${n}.mp3`,
    title: `T${n}`
  }) as Track

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('Player.load rejection staleness', () => {
  it('a load rejected after stop() does not clobber the stopped state', async () => {
    const player = new LoadTestPlayer()
    player.load(track(1))
    player.stop()

    player.rejectLoad(0, new Error('LOAD_INTERRUPTED'))
    await tick()

    expect(player.read().state).toBe('stopped')
  })

  it('a load rejected after a newer load() stays silent', async () => {
    const player = new LoadTestPlayer()
    player.load(track(1))
    player.load(track(2))
    const unloadsBefore = player.unloadCalls

    player.rejectLoad(0, new Error('LOAD_INTERRUPTED'))
    await tick()

    // The stale rejection must not set error state or unload the new load.
    expect(player.read().state).not.toBe('error')
    expect(player.unloadCalls).toBe(unloadsBefore)
  })

  it('the active load still surfaces its own failure', async () => {
    const player = new LoadTestPlayer()
    player.load(track(1))

    player.rejectLoad(0, new Error('boom'))
    await tick()

    expect(player.read().state).toBe('error')
  })
})

/**
 * The URL handed to Shaka can differ from the one the caller queued — a
 * followed redirect, or a `transform` that rewrites it. Callers address tracks
 * by their own `src` (mapping it back to their own content id, say), so a load
 * must not rewrite the track's identity to a transport detail.
 */
describe('Player.load track identity', () => {
  class SrcCapturingPlayer extends Player {
    loadedSrc?: string

    constructor() {
      super()
      this.element = {
        play: () => Promise.resolve(),
        pause: () => {}
      } as unknown as HTMLMediaElement
      this.player = {
        load: (src: string) => {
          this.loadedSrc = src
          return Promise.resolve()
        },
        unload: () => Promise.resolve()
      } as unknown as typeof this.player
    }

    headers(): Record<string, string> | undefined {
      return this.mediaHeaders
    }
  }

  const queued = {
    id: 't1',
    src: 'https://api.example.com/stream/content/7',
    title: 'T1'
  } as Track
  const resolved = 'https://cdn.example.com/track.mp3?sig=abc'

  it('plays the resolved url but keeps the queued track as current', async () => {
    const player = new SrcCapturingPlayer()

    player.load(queued, undefined, { src: resolved })
    await tick()

    expect(player.loadedSrc).toBe(resolved)
    expect(player.current?.src).toBe(queued.src)
  })

  it('reports the queued track to onLoaded', async () => {
    const player = new SrcCapturingPlayer()
    const onLoaded = vi.fn()

    player.load(queued, onLoaded, { src: resolved })
    await tick()

    expect(onLoaded).toHaveBeenCalledWith(
      expect.objectContaining({ src: queued.src })
    )
  })

  it('falls back to the track src when no override is given', async () => {
    const player = new SrcCapturingPlayer()

    player.load(queued)
    await tick()

    expect(player.loadedSrc).toBe(queued.src)
    expect(player.current?.src).toBe(queued.src)
  })

  it('stores headers for the request filter, ignoring an empty set', async () => {
    const player = new SrcCapturingPlayer()

    player.load(queued, undefined, { headers: { Authorization: 'Bearer t' } })
    await tick()
    expect(player.headers()).toEqual({ Authorization: 'Bearer t' })

    player.load(queued, undefined, { headers: {} })
    await tick()
    expect(player.headers()).toBeUndefined()
  })
})

/**
 * A track that fails to load is still the active track. Native derives the
 * active track from the queue, so an app always has something to attach a
 * playback error to; web tying it to load success meant a failed track left
 * `getActiveTrack()` undefined, and UIs keyed on it rendered nothing at all —
 * the error had nowhere to appear.
 */
describe('Player.load active track on failure', () => {
  const track = (n: number): Track =>
    ({
      id: `t${n}`,
      src: `https://example.com/${n}.mp3`,
      title: `T${n}`
    }) as Track

  it('keeps the failed track as current so an error has a home', async () => {
    const player = new LoadTestPlayer()

    player.load(track(1))
    expect(player.current?.src).toBe(track(1).src)

    player.rejectLoad(0, new Error('401'))
    await tick()

    expect(player.current?.src).toBe(track(1).src)
    expect(player.read().state).toBe('error')
  })

  it('reflects the newest attempt when a load supersedes another', async () => {
    const player = new LoadTestPlayer()

    player.load(track(1))
    player.load(track(2))

    expect(player.current?.src).toBe(track(2).src)

    // the superseded load rejecting must not resurrect the older track
    player.rejectLoad(0, new Error('stale'))
    await tick()

    expect(player.current?.src).toBe(track(2).src)
  })

  it('is set for an unplayable track with no src', async () => {
    const player = new LoadTestPlayer()

    player.load({ id: 'x', title: 'No Src' } as Track)
    await tick()

    expect(player.current?.id).toBe('x')
    expect(player.read().state).toBe('error')
  })
})
