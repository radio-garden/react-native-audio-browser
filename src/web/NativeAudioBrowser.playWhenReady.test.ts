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
    // re-armed _isStopped = false and re-entered super.load() after stop().
    expect(browser.getPlayback().state).toBe('stopped')
    // `current` is the track, not undefined: the transition is announced when
    // load() is called, before the URL resolves, so by the time stop() runs the
    // track is already active — and `Player.stop()` deliberately keeps it so
    // play() can resume. Android behaves the same way; it was only undefined
    // here because the async gap meant it had never been set.
    expect(browser.current?.id).toBe(track.id)
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

describe('NativeAudioBrowser getQueueVersion', () => {
  class QueueBrowser extends TestBrowser {
    load(): void {}
  }

  it('changes with every change to the queue', () => {
    const browser = new QueueBrowser()
    const versions = [browser.getQueueVersion()]
    const changes = [
      () => browser.setQueue([track, { ...track, id: 't2' }], 0),
      () => browser.add([{ ...track, id: 't3' }]),
      () => browser.move(0, 1),
      () => browser.remove([2]),
      () => browser.removeUpcomingTracks()
    ]
    for (const change of changes) {
      change()
      versions.push(browser.getQueueVersion())
    }
    expect(new Set(versions).size).toBe(versions.length)
  })

  it('stays while the queue does', () => {
    const browser = new QueueBrowser()
    browser.setQueue([track, { ...track, id: 't2' }], 0)
    const version = browser.getQueueVersion()

    browser.skip(1)

    expect(browser.getQueueVersion()).toBe(version)
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
  it('passes startPosition through to skip() in seconds', () => {
    const skips: Array<[number, number | undefined]> = []
    class SkipRecordingBrowser extends TestBrowser {
      skip(index: number, initialPosition?: number): void {
        skips.push([index, initialPosition])
      }
    }
    const browser = new SkipRecordingBrowser()

    browser.setQueue([track], 0, 30)

    expect(skips).toEqual([[0, 30]])
  })
})

/**
 * Android emits the active-track transition from ExoPlayer's
 * `onMediaItemTransition`, which fires when the media item is set — before the
 * load has resolved, and regardless of whether it ever does. iOS drives it from
 * the queue coordinator, which behaves the same. Web announcing it only on
 * success meant a track that failed to load never became the active track for
 * consumers, so a UI bound to `useActiveTrack()` had nothing to render and a
 * playback error had nowhere to appear.
 */
describe('NativeAudioBrowser active track announced on the load attempt', () => {
  class FailingLoadBrowser extends TestBrowser {
    constructor(loadResult: () => Promise<void>) {
      super()
      this.player = {
        load: loadResult,
        unload: () => Promise.resolve()
      } as unknown as typeof this.player
    }

    /** getNowPlaying() reads progress off the element, which needs buffered ranges. */
    withProgressCapableElement(): this {
      this.element = {
        play: () => Promise.resolve(),
        pause: () => {},
        currentTime: 0,
        duration: 100,
        buffered: { length: 0, end: () => 0 }
      } as unknown as HTMLMediaElement
      return this
    }
  }

  const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

  it('announces the transition even when the load fails', async () => {
    const browser = new FailingLoadBrowser(() =>
      Promise.reject(new Error('401'))
    )
    const announced: (Track | undefined)[] = []
    browser.onPlaybackActiveTrackChanged = (event) =>
      announced.push(event.track)

    browser.load(track)
    await tick()

    expect(announced.map((t) => t?.src)).toContain(track.src)
    expect(browser.getActiveTrack()?.src).toBe(track.src)
  })

  it('announces the transition exactly once on a successful load', async () => {
    const browser = new FailingLoadBrowser(() => Promise.resolve())
    const announced: (Track | undefined)[] = []
    browser.onPlaybackActiveTrackChanged = (event) =>
      announced.push(event.track)

    browser.load(track)
    await tick()

    expect(announced).toHaveLength(1)
    expect(announced[0]?.src).toBe(track.src)
  })

  it('does not report a load failure when the announcement throws', async () => {
    // The announcement runs after the load has committed, so a consumer's own
    // throwing handler must not travel back up and error a load that worked.
    const browser = new FailingLoadBrowser(() => Promise.resolve())
    browser.onPlaybackActiveTrackChanged = () => {
      throw new Error('consumer handler blew up')
    }
    const errors: (string | undefined)[] = []
    browser.onPlaybackError = (event) => errors.push(event.error?.code)

    browser.load(track)
    await tick()

    expect(errors).not.toContain('load-error')
    expect(browser.getPlayback().state).not.toBe('error')
  })

  it('announces the transition before the error for a track with no src', async () => {
    // super.load() sets the error state synchronously when there is nothing to
    // play, so announcing after it put onPlaybackError first — and a consumer
    // that clears its banner on a track change wiped the error. Native cannot
    // invert these: Android fires onMediaItemTransition at prepare, onPlayerError
    // only after.
    const browser = new FailingLoadBrowser(() => Promise.resolve())
    const order: string[] = []
    browser.onPlaybackActiveTrackChanged = () => order.push('track-changed')
    browser.onPlaybackError = (event) => {
      if (event.error) order.push('error')
    }

    browser.load({ id: 'no-src', title: 'No src' })
    await tick()

    expect(order).toEqual(['track-changed', 'error'])
  })

  it('does not report loading before the active track change', async () => {
    // Native emits these in the opposite order — Android's active-track
    // callback comes from onMediaItemTransition, its LOADING from onEvents,
    // which ExoPlayer runs after the individual callbacks. Dispatching
    // `trackLoading` when load() is called inverted that, handing a consumer
    // that reads getActiveTrack() on the loading edge the outgoing track.
    const browser = new FailingLoadBrowser(() => Promise.resolve())
    const order: string[] = []
    browser.onPlaybackChanged = (playback) => {
      if (playback.state === 'loading') order.push('loading')
    }
    browser.onPlaybackActiveTrackChanged = () => order.push('active-track')

    browser.load(track)
    // both land synchronously, in native's order: the transition is announced
    // when the caller asks for it, and `loading` covers the resolution that
    // follows
    expect(order).toEqual(['active-track', 'loading'])

    await tick()
    expect(order.indexOf('active-track')).toBeLessThan(order.indexOf('loading'))
  })

  it('still publishes now-playing when the announcement throws', async () => {
    // The two are independent: a consumer's throwing handler must not leave the
    // lock screen showing the previous track.
    const browser = new FailingLoadBrowser(() =>
      Promise.resolve()
    ).withProgressCapableElement()
    browser.onPlaybackActiveTrackChanged = () => {
      throw new Error('consumer handler blew up')
    }
    const published: (string | undefined)[] = []
    browser.onNowPlayingChanged = (metadata) => published.push(metadata.title)

    browser.load(track)
    await tick()

    expect(published).toContain(track.title)
  })
})

/**
 * The media path reads the *resolved* request layer, so unlike browse it can be
 * handed a rejected resolver. A track whose `src` is already absolute needs no
 * layer at all, and native degrades rather than failing — `getMediaRequestConfig`
 * returns null and the original URL plays on.
 */
describe('NativeAudioBrowser media load vs a failing request resolver', () => {
  const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

  it('still plays an absolute src when the request resolver rejects', async () => {
    const browser = new TestBrowser()
    browser.configuration = {
      requestResolver: () => Promise.reject(new Error('token refresh failed'))
    }
    const errors: (string | undefined)[] = []
    browser.onPlaybackError = (event) => errors.push(event.error?.code)

    browser.load(track)
    await tick()

    expect(errors).not.toContain('load-error')
    expect(browser.getActiveTrack()?.src).toBe(track.src)
  })
})

/**
 * Both native platforms clear the playback error when the state leaves `error`,
 * and say so: Android emits `onPlaybackError(null)` when leaving ERROR, iOS
 * clears `playbackError` before emitting the state change. Web derived the
 * cleared value correctly from state but never emitted it, so a consumer
 * subscribed to the event kept showing a stale error after the next track
 * loaded fine.
 */
describe('NativeAudioBrowser playback error lifecycle', () => {
  class ErrorStateBrowser extends TestBrowser {
    setErrorState(code: string): void {
      this.state = {
        state: 'error',
        error: { kind: 'unknown', code, message: code }
      }
    }

    setPlainState(state: PlaybackState): void {
      this.state = { state }
    }
  }

  const collect = () => {
    const browser = new ErrorStateBrowser()
    const events: (string | undefined)[] = []
    browser.onPlaybackError = (event) => events.push(event.error?.code)
    return { browser, events }
  }

  it('emits the error, then emits the clear when leaving the error state', () => {
    const { browser, events } = collect()

    browser.setErrorState('shaka-1001')
    expect(events).toEqual(['shaka-1001'])
    expect(browser.getPlaybackError()?.code).toBe('shaka-1001')

    browser.setPlainState('loading')
    expect(events).toEqual(['shaka-1001', undefined])
    expect(browser.getPlaybackError()).toBeUndefined()
  })

  it('does not emit a clear when no error preceded the transition', () => {
    const { browser, events } = collect()

    browser.setPlainState('loading')
    browser.setPlainState('playing')

    expect(events).toEqual([])
  })

  it('emits the new error when one error replaces another', () => {
    const { browser, events } = collect()

    browser.setErrorState('shaka-1001')
    browser.setErrorState('shaka-3016')

    expect(events).toEqual(['shaka-1001', 'shaka-3016'])
  })
})
