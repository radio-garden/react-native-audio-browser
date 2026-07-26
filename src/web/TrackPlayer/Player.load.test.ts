import { describe, expect, it } from 'vitest'
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
  ({ id: `t${n}`, src: `https://example.com/${n}.mp3`, title: `T${n}` }) as Track

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
