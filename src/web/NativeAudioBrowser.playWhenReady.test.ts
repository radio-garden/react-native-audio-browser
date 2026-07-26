import { describe, expect, it } from 'vitest'
import type { PlaybackState } from '../features'
import type { Track } from '../types'
import { NativeAudioBrowser } from './NativeAudioBrowser'

// Transport calls must emit the intent change through the playWhenReady
// accessor override — they previously wrote the raw base field, so JS
// consumers never heard about play()/pause()/stop() intent changes (only
// setPlayWhenReady()'s) and MediaSession never synced.
class TestBrowser extends NativeAudioBrowser {
  playCalls = 0
  pauseCalls = 0

  constructor() {
    super()
    // Minimal fakes so transport calls run without setupPlayer/DOM.
    this.element = {
      play: () => {
        this.playCalls++
        return Promise.resolve()
      },
      pause: () => {
        this.pauseCalls++
      }
    } as unknown as HTMLMediaElement
    this.player = {
      load: () => Promise.resolve(),
      unload: () => Promise.resolve()
    } as unknown as typeof this.player
  }

  forceState(state: PlaybackState): void {
    this.state = { state }
  }
}

const track: Track = {
  id: 't1',
  src: 'https://example.com/audio.mp3',
  title: 'Test Track'
}

function makeBrowser(): { browser: TestBrowser; emitted: boolean[] } {
  const browser = new TestBrowser()
  const emitted: boolean[] = []
  browser.onPlaybackPlayWhenReadyChanged = (event) =>
    emitted.push(event.playWhenReady)
  return { browser, emitted }
}

describe('NativeAudioBrowser playWhenReady emission', () => {
  it('play() emits the intent change', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    expect(emitted).toEqual([true])
  })

  it('pause() emits the intent change', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    browser.pause()
    expect(emitted).toEqual([true, false])
  })

  it('stop() emits the intent change', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    browser.stop()
    expect(emitted).toEqual([true, false])
  })

  it('does not emit when the value is unchanged', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    browser.play()
    expect(emitted).toEqual([true])
  })
})

// setPlayWhenReady must drive the engine like native, not just the flag:
// audio kept playing after setPlayWhenReady(false) while every event and
// MediaSession reported paused, and true from 'paused' never resumed.
describe('NativeAudioBrowser setPlayWhenReady drives the engine', () => {
  it('false pauses the element', () => {
    const { browser } = makeBrowser()
    browser.play()
    browser.forceState('playing')

    browser.setPlayWhenReady(false)

    expect(browser.pauseCalls).toBe(1)
    expect(browser.getPlayWhenReady()).toBe(false)
  })

  it('true from paused resumes the element', () => {
    const { browser } = makeBrowser()
    browser.current = track
    browser.forceState('paused')

    browser.setPlayWhenReady(true)

    expect(browser.playCalls).toBe(1)
  })

  it('true while loading only sets the flag (load auto-plays)', () => {
    const { browser } = makeBrowser()
    browser.current = track
    browser.forceState('loading')

    browser.setPlayWhenReady(true)

    expect(browser.playCalls).toBe(0)
    expect(browser.getPlayWhenReady()).toBe(true)
  })
})

// Intent-only changes alter the derived playing/buffering flags without a
// state transition (e.g. pause during 'loading') — they must emit too, and
// identical derivations must not double-emit (parity with Android's
// refreshPlayingState dedupe).
describe('NativeAudioBrowser playing-state emission', () => {
  it('emits on an intent-only change', () => {
    const { browser } = makeBrowser()
    const states: Array<{ playing: boolean; buffering: boolean }> = []
    browser.onPlaybackPlayingState = (s) =>
      states.push({ playing: s.playing, buffering: s.buffering })
    browser.forceState('loading')
    browser.setPlayWhenReady(true)
    states.length = 0

    browser.setPlayWhenReady(false)

    expect(states).toEqual([{ playing: false, buffering: false }])
  })

  it('does not re-emit an identical derivation across state changes', () => {
    const { browser } = makeBrowser()
    const states: Array<{ playing: boolean }> = []
    browser.onPlaybackPlayingState = (s) => states.push({ playing: s.playing })

    browser.forceState('paused')
    browser.forceState('stopped')

    // pwr is false throughout: both derive {playing:false,buffering:false}.
    expect(states.length).toBe(1)
  })
})

describe('NativeAudioBrowser stop vs in-flight load', () => {
  it('stop() invalidates a load still resolving its URL', async () => {
    const { browser } = makeBrowser()
    browser.load(track)
    browser.stop()

    // Let the load's post-await continuation run.
    await new Promise((resolve) => setTimeout(resolve, 0))

    // The stale load must not revive the player: previously its continuation
    // re-armed _isStopped = false and set the current track after stop().
    expect(browser.current).toBeUndefined()
    expect(browser.getPlayback().state).toBe('stopped')
  })
})

// Queue mutations must emit onPlaybackQueueChanged (the JS contract documents
// "added, removed, reordered"; Android emits via onTimelineChanged) — only
// load/setQueue did on web, so useQueue() went stale after any mutation.
describe('NativeAudioBrowser queue change events', () => {
  function makeQueueBrowser(): {
    browser: TestBrowser
    lengths: number[]
  } {
    class QueueEventBrowser extends TestBrowser {
      load(): void {}
    }
    const browser = new QueueEventBrowser()
    const lengths: number[] = []
    browser.onPlaybackQueueChanged = (queue) => lengths.push(queue.length)
    browser.setQueue([track, { ...track, id: 't2' }], 0)
    lengths.length = 0
    return { browser, lengths }
  }

  it('add emits', () => {
    const { browser, lengths } = makeQueueBrowser()
    browser.add([{ ...track, id: 't3' }])
    expect(lengths).toEqual([3])
  })

  it('remove emits', () => {
    const { browser, lengths } = makeQueueBrowser()
    browser.remove([1])
    expect(lengths).toEqual([1])
  })

  it('move emits', () => {
    const { browser, lengths } = makeQueueBrowser()
    browser.move(0, 1)
    expect(lengths).toEqual([2])
  })

  it('removeUpcomingTracks emits', () => {
    const { browser, lengths } = makeQueueBrowser()
    browser.removeUpcomingTracks()
    expect(lengths).toEqual([1])
  })

  it('reset emits the emptied queue', () => {
    const { browser, lengths } = makeQueueBrowser()
    browser.reset()
    expect(lengths).toEqual([0])
  })
})

// The queue array must never leak by live reference: in-place mutations
// (add/move) otherwise emit the same object React already holds, and the
// useState Object.is bailout suppresses the re-render the event exists for.
describe('NativeAudioBrowser queue reference freshness', () => {
  it('emits a fresh array on each mutation', () => {
    class QueueEventBrowser extends TestBrowser {
      load(): void {}
    }
    const browser = new QueueEventBrowser()
    const seen: object[] = []
    browser.onPlaybackQueueChanged = (queue) => seen.push(queue)
    browser.setQueue([track, { ...track, id: 't2' }], 0)

    browser.add([{ ...track, id: 't3' }])
    browser.move(0, 1)

    expect(seen[1]).not.toBe(seen[0])
    expect(seen[2]).not.toBe(seen[1])
    expect(seen[2]).not.toBe(browser.getQueue())
  })

  it('getQueue returns a defensive copy', () => {
    class QueueEventBrowser extends TestBrowser {
      load(): void {}
    }
    const browser = new QueueEventBrowser()
    browser.setQueue([track, { ...track, id: 't2' }], 0)

    browser.getQueue().length = 0

    expect(browser.getQueue().length).toBe(2)
  })
})

// Halting must precede the fade-cancel volume restore — the reverse lets
// full-volume audio slip out while the element is still playing.
describe('NativeAudioBrowser sleep-fade halt order', () => {
  it('setPlayWhenReady(false) pauses before restoring the fading volume', () => {
    const order: string[] = []
    class FadeBrowser extends NativeAudioBrowser {
      constructor() {
        super()
        this.element = {
          play: () => Promise.resolve(),
          pause: () => {
            order.push('pause')
          },
          get volume() {
            return 1
          },
          set volume(_v: number) {
            order.push('volume')
          }
        } as unknown as HTMLMediaElement
        this.player = {
          unload: () => Promise.resolve()
        } as unknown as typeof this.player
      }
    }
    const browser = new FadeBrowser()
    ;(
      browser as unknown as { sleepFader: { start(d: number): void } }
    ).sleepFader.start(10)
    order.length = 0

    browser.setPlayWhenReady(false)

    expect(order[0]).toBe('pause')
    expect(order).toContain('volume')
  })
})

describe('NativeAudioBrowser setQueue start position', () => {
  it('passes startPositionMs to skip() in seconds', () => {
    const skips: Array<[number, number | undefined]> = []
    class SkipRecordingBrowser extends TestBrowser {
      skip(index: number, initialPosition?: number): void {
        skips.push([index, initialPosition])
      }
    }
    const browser = new SkipRecordingBrowser()

    browser.setQueue([track], 0, 30000)

    expect(skips).toEqual([[0, 30]])
  })
})
