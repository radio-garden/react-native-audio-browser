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

// Uses the real onTrackEnded (unlike TestQueuePlayer) so the natural-end path
// runs through to onQueueEnded.
class TestQueueEndPlayer extends QueuePlayer {
  emit(event: PlaybackEvent): void {
    this.dispatch(event)
  }
}

// Overrides onQueueEnded without calling super — the intent clear must not
// depend on subclasses remembering to (NativeAudioBrowser overrides this hook).
class OverridingQueueEndPlayer extends QueuePlayer {
  queueEndedCount = 0
  emit(event: PlaybackEvent): void {
    this.dispatch(event)
  }
  protected onQueueEnded(): void {
    this.queueEndedCount++
  }
}

// Records the relative order of the intent drop, the state landing, and the
// queue-ended hook — pinned to the native order (intent → state → queueEnded).
class OrderRecordingPlayer extends QueuePlayer {
  order: string[] = []
  emit(event: PlaybackEvent): void {
    this.dispatch(event)
  }
  protected get state(): Playback {
    return super.state
  }
  protected set state(newState: Playback) {
    super.state = newState
    this.order.push(`state:${newState.state}`)
  }
  public get playWhenReady(): boolean {
    return super.playWhenReady
  }
  public set playWhenReady(pwr: boolean) {
    super.playWhenReady = pwr
    this.order.push(`pwr:${pwr}`)
  }
  protected onQueueEnded(): void {
    super.onQueueEnded()
    this.order.push('queueEnded')
  }
}

describe('QueuePlayer queue end', () => {
  it('drops the play intent when the queue ends naturally', () => {
    const player = new TestQueueEndPlayer()
    player.playWhenReady = true

    player.emit({ type: 'trackEndedNaturally' })

    // A natural end exhausts the intent: keeping it set inverted
    // togglePlayback (first press was a silent pause) and armed load()'s
    // auto-play with phantom intent.
    expect(player.playWhenReady).toBe(false)
  })

  it('drops the intent even when onQueueEnded overrides without super', () => {
    const player = new OverridingQueueEndPlayer()
    player.playWhenReady = true

    player.emit({ type: 'trackEndedNaturally' })

    expect(player.queueEndedCount).toBe(1)
    expect(player.playWhenReady).toBe(false)
  })

  it('orders intent → state → queueEnded, matching native', () => {
    const player = new OrderRecordingPlayer()
    player.playWhenReady = true
    player.order.length = 0

    player.emit({ type: 'trackEndedNaturally' })

    expect(player.order).toEqual(['pwr:false', 'state:ended', 'queueEnded'])
  })
})

describe('QueuePlayer remove without a current track', () => {
  it('keeps the shuffle order aligned with the post-remove layout', () => {
    class ShuffleTestPlayer extends QueuePlayer {
      seed(tracks: Array<{ src: string }>): void {
        this.queue.setTracks(tracks as never)
      }
      setCurrent(index: number): void {
        this.queue.currentIndex = index
      }
      neighbours(): [number | undefined, number | undefined] {
        return [this.queue.previousIndex(), this.queue.nextIndex()]
      }
    }
    const player = new ShuffleTestPlayer()
    player.setShuffleEnabled(true)
    player.seed([{ src: 'a' }, { src: 'b' }])

    // 'no-current' outcome: tracks are still spliced, so a stale order would
    // keep indexing the pre-remove layout.
    player.remove([0])

    // One track left: a stale two-entry order (either permutation) would give
    // the survivor a neighbour that no longer exists.
    player.setCurrent(0)
    expect(player.neighbours()).toEqual([undefined, undefined])
  })
})

describe('QueuePlayer skip while stopped', () => {
  it('reloads instead of seeking the unloaded element', () => {
    const loads: string[] = []
    class SkipTestPlayer extends QueuePlayer {
      load(track: { src?: string }): void {
        loads.push(track.src ?? '')
      }
      seekTo(): void {}
      forceStopped(): void {
        this._isStopped = true
      }
    }
    const player = new SkipTestPlayer()
    player.add([{ src: 's0' } as never])
    expect(loads).toEqual(['s0'])
    player.forceStopped()

    player.skip(0)

    // Same-index skip took the seek branch, which is dead while stopped.
    expect(loads).toEqual(['s0', 's0'])
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
