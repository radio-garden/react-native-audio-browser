import { describe, expect, it } from 'vitest'
import type { Playback, PlaybackState } from '../../features'
import type { PlaybackEvent } from './PlaybackStateMachine'
import { Player } from './Player'
import { QueuePlayer } from './QueuePlayer'

// Exercises the integration seam between the element/Shaka events and the state
// machine — the web-specific behaviour the pure machine test doesn't cover.
class TestPlayer extends Player {
  forceState(state: PlaybackState): void {
    this.state = { state }
  }
  forceStopped(stopped: boolean): void {
    this._isStopped = stopped
  }
  emit(event: PlaybackEvent): void {
    this.dispatch(event)
  }
  read(): Playback {
    return this.state
  }
}

class TestQueuePlayer extends QueuePlayer {
  endedCount = 0
  protected onTrackEnded(): void {
    this.endedCount++
  }
  forceStopped(stopped: boolean): void {
    this._isStopped = stopped
  }
  emit(event: PlaybackEvent): void {
    this.dispatch(event)
  }
}

describe('Player.dispatch', () => {
  it('keeps the error when the post-unload pause arrives (the documented bug)', () => {
    const player = new TestPlayer()
    player.forceState('error')

    // onError() unloads, which makes the element emit `pause` with the track
    // still set. Pre-machine this clobbered the error with `paused`.
    player.emit({ type: 'paused', hasAsset: true })

    expect(player.read().state).toBe('error')
  })

  it('ignores element/Shaka events while stopped', () => {
    const player = new TestPlayer()
    player.forceState('stopped')
    player.forceStopped(true)

    player.emit({ type: 'playing' })
    player.emit({ type: 'waiting' })

    expect(player.read().state).toBe('stopped')
  })

  it('stays playing when a rebuffer finishes mid-playback (no ready flash)', () => {
    const player = new TestPlayer()
    player.forceState('playing')

    player.emit({ type: 'bufferingSufficient' })

    expect(player.read().state).toBe('playing')
  })

  it('settles to ready after a load completes', () => {
    const player = new TestPlayer()
    player.forceState('loading')

    player.emit({ type: 'loadSeekCompleted' })

    expect(player.read().state).toBe('ready')
  })
})

describe('QueuePlayer queue advance', () => {
  it('advances the queue when a track ends naturally', () => {
    const player = new TestQueuePlayer()

    player.emit({ type: 'trackEndedNaturally' })

    expect(player.endedCount).toBe(1)
  })

  it('does not advance the queue on an end event while stopped', () => {
    const player = new TestQueuePlayer()
    player.forceStopped(true)

    player.emit({ type: 'trackEndedNaturally' })

    expect(player.endedCount).toBe(0)
  })
})
